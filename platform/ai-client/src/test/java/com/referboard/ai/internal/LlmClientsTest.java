package com.referboard.ai.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.referboard.ai.LlmCallException;
import com.referboard.ai.StructuredPrompt;
import com.referboard.ai.TextPrompt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withRawStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LlmClientsTest {

    record Person(String name, List<String> skills) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final RetryingCaller noWaitRetry = new RetryingCaller(3, d -> { });

    private StructuredPrompt<Person> prompt() throws Exception {
        return StructuredPrompt.of("system rules", "resume text", "record_person", "Record the person",
                mapper.readTree("{\"type\":\"object\",\"properties\":{\"name\":{\"type\":\"string\"}}}"), Person.class);
    }

    @Test
    void anthropicForcesToolCallAndMapsToolInput() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.anthropic.com/v1/messages"))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(header("x-api-key", "sk-test"))
                .andExpect(header("anthropic-version", "2023-06-01"))
                .andExpect(jsonPath("$.model").value("claude-test"))
                .andExpect(jsonPath("$.system").value("system rules"))
                .andExpect(jsonPath("$.tool_choice.type").value("tool"))
                .andExpect(jsonPath("$.tool_choice.name").value("record_person"))
                .andExpect(jsonPath("$.tools[0].input_schema.type").value("object"))
                .andRespond(withSuccess("""
                        {"content":[{"type":"text","text":"ok"},
                          {"type":"tool_use","name":"record_person","input":{"name":"Asha","skills":["java"]}}],
                         "stop_reason":"tool_use"}""", MediaType.APPLICATION_JSON));

        var client = new AnthropicLlmClient(builder, mapper, "sk-test", "claude-test", null, noWaitRetry);
        Person person = client.structured(prompt());

        assertThat(person).isEqualTo(new Person("Asha", List.of("java")));
        assertThat(client.modelId()).isEqualTo("anthropic:claude-test");
        server.verify();
    }

    @Test
    void retriesOverloadedThenSucceeds() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(times(2), requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withRawStatus(529)); // Anthropic "overloaded"
        server.expect(once(), requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\" Hello \"}]}",
                        MediaType.APPLICATION_JSON));

        var client = new AnthropicLlmClient(builder, mapper, "sk", "m", null, noWaitRetry);

        assertThat(client.text(TextPrompt.of("s", "u"))).isEqualTo("Hello");
        server.verify();
    }

    @Test
    void doesNotRetryClientErrors() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"error\":\"invalid x-api-key\"}"));

        var client = new AnthropicLlmClient(builder, mapper, "bad", "m", null, noWaitRetry);

        assertThatThrownBy(() -> client.text(TextPrompt.of("s", "u")))
                .isInstanceOf(LlmCallException.class)
                .hasMessageContaining("401");
        server.verify();
    }

    @Test
    void anthropicWithoutToolUseIsAnError() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.anthropic.com/v1/messages"))
                .andRespond(withSuccess("{\"content\":[{\"type\":\"text\",\"text\":\"I refuse\"}],"
                        + "\"stop_reason\":\"end_turn\"}", MediaType.APPLICATION_JSON));

        var client = new AnthropicLlmClient(builder, mapper, "sk", "m", null, noWaitRetry);
        var p = prompt();

        assertThatThrownBy(() -> client.structured(p)).isInstanceOf(LlmCallException.class)
                .hasMessageContaining("no tool_use");
    }

    @Test
    void openAiUsesJsonSchemaResponseFormat() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.openai.com/v1/chat/completions"))
                .andExpect(header("Authorization", "Bearer sk-oa"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.name").value("record_person"))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andRespond(withSuccess("""
                        {"choices":[{"message":{"content":"{\\"name\\":\\"Ravi\\",\\"skills\\":[]}"},
                          "finish_reason":"stop"}]}""", MediaType.APPLICATION_JSON));

        var client = new OpenAiLlmClient(builder, mapper, "sk-oa", "gpt-test", null, noWaitRetry);

        assertThat(client.structured(prompt()).name()).isEqualTo("Ravi");
        server.verify();
    }

    @Test
    void openAiEmbeddingsAreReorderedByIndex() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo("https://api.openai.com/v1/embeddings"))
                .andExpect(jsonPath("$.dimensions").value(2))
                .andExpect(jsonPath("$.input.length()").value(2))
                .andRespond(withSuccess("""
                        {"data":[{"index":1,"embedding":[0.0,1.0]},{"index":0,"embedding":[1.0,0.0]}]}""",
                        MediaType.APPLICATION_JSON));

        var client = new OpenAiEmbeddingClient(builder, mapper, "sk", "text-embedding-3-small", 2, null, noWaitRetry);
        var vectors = client.embedAll(List.of("first", "second"));

        assertThat(vectors.get(0)).containsExactly(1f, 0f);
        assertThat(vectors.get(1)).containsExactly(0f, 1f);
        assertThat(client.model()).isEqualTo("openai:text-embedding-3-small:2");
    }
}
