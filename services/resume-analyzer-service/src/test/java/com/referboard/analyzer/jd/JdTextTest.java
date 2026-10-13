package com.referboard.analyzer.jd;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdTextTest {

    @Test
    void htmlBecomesOneLinePerBlockWithBullets() {
        String text = JdText.fromHtml("<h2>Requirements</h2><ul><li>Java <b>17</b></li><li>Kafka</li></ul><p>Apply&nbsp;now</p>");
        assertThat(text).isEqualTo("Requirements\n- Java 17\n- Kafka\nApply now");
    }

    @Test
    void normalisesBulletsAndWhitespace() {
        assertThat(JdText.normalize("  •  Go \r\n\r\n\t* Rust\n-Python  ")).isEqualTo("- Go\n- Rust\n- Python");
    }

    @Test
    void hashIgnoresCase() {
        assertThat(JdText.hash("Java Engineer")).isEqualTo(JdText.hash("java engineer")).hasSize(64);
    }
}
