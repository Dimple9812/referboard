package com.referboard.profile.parsing;

import com.referboard.common.web.ApiException;
import com.referboard.profile.testdata.Documents;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextExtractorTest {

    private final TextExtractor extractor = new TextExtractor();

    @Test
    void extractsPdf() throws Exception {
        var out = extractor.extract(Documents.pdf(HeuristicResumeParserTest.sample()), "cv.pdf");

        assertThat(out.mediaType()).isEqualTo(TextExtractor.PDF);
        assertThat(out.text()).contains("Priya Sharma").contains("Senior Software Engineer | Razorpay");
    }

    @Test
    void extractsDocx() throws Exception {
        var out = extractor.extract(Documents.docx(HeuristicResumeParserTest.sample()), "cv.docx");

        assertThat(out.mediaType()).isEqualTo(TextExtractor.DOCX);
        assertThat(out.text()).contains("Indian Institute of Technology, Roorkee");
    }

    @Test
    void detectsTypeFromBytesNotFromTheName() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R'};

        assertThatThrownBy(() -> extractor.extract(png, "totally-a-resume.pdf"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "UNSUPPORTED_FILE_TYPE");
    }

    @Test
    void rejectsDocumentsWithoutText() throws Exception {
        assertThatThrownBy(() -> extractor.extract(Documents.pdf(" "), "scan.pdf"))
                .hasFieldOrPropertyWithValue("code", "NO_TEXT_FOUND");
    }

    @Test
    void plainTextIsAccepted() {
        byte[] txt = ("Plain text resume for someone who writes Java and Spring Boot services every day. "
                + "Experience: lots.").getBytes(StandardCharsets.UTF_8);
        assertThat(extractor.extract(txt, "cv.txt").mediaType()).isEqualTo(TextExtractor.TXT);
    }
}
