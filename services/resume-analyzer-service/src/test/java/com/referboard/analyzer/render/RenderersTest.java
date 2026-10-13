package com.referboard.analyzer.render;

import com.referboard.analyzer.Fixtures;
import com.referboard.analyzer.model.ResumeContent;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class RenderersTest {

    record Extracted(String text, Metadata metadata) {
    }

    static Extracted extract(byte[] bytes) throws Exception {
        BodyContentHandler h = new BodyContentHandler(-1);
        Metadata md = new Metadata();
        new AutoDetectParser().parse(new ByteArrayInputStream(bytes), h, md, new ParseContext());
        return new Extracted(h.toString(), md);
    }

    @Test
    void docxIsRealTextWithStandardHeadings() throws Exception {
        Extracted e = extract(DocxRenderer.render(Fixtures.resume().withSummary("Backend engineer → payments.")));
        assertThat(e.metadata().get("Content-Type")).contains("wordprocessingml");
        assertThat(e.text()).contains("Asha Rao", "asha.rao@example.com", "SUMMARY", "SKILLS", "EXPERIENCE",
                "EDUCATION", "Senior Software Engineer, Lumen Pay", "Mar 2021 – Present",
                "Cut p99 latency of the payouts API by 40%", "B.Tech, Computer Science", "→");
    }

    @Test
    void pdfIsSelectableTextOnOnePage() throws Exception {
        ResumeContent r = Fixtures.resume().withSummary("Backend engineer → payments, ₹2Cr GMV — “quoted”.");
        Extracted e = extract(PdfRenderer.render(r));
        assertThat(e.metadata().get("xmpTPg:NPages")).isEqualTo("1");
        assertThat(e.text()).contains("Asha Rao", "EXPERIENCE", "Jul 2018 – Feb 2021",
                "•  Built Spring Boot services in Java", "Backend engineer -> payments, INR 2Cr GMV — “quoted”.");
    }

    @Test
    void pdfDropsCharactersStandardFontsCantShow() {
        assertThat(PdfRenderer.clean("Résumé — नमस्ते ok ✓")).isEqualTo("Résumé —  ok -");
    }
}
