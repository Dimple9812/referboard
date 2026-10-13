package com.referboard.profile.testdata;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;

/** Builds real PDF/DOCX files in memory so extraction is tested against genuine formats, not mocks. */
public final class Documents {

    private Documents() {
    }

    /** The shared sample resume (src/test/resources/fixtures/sample-resume.txt). */
    public static String sampleResumeText() {
        try (var in = java.util.Objects.requireNonNull(
                Documents.class.getResourceAsStream("/fixtures/sample-resume.txt"))) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    public static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            PDPageContentStream content = new PDPageContentStream(doc, page);
            content.beginText();
            content.setFont(font, 10);
            content.setLeading(13);
            content.newLineAtOffset(40, 800);
            int lines = 0;
            for (String line : text.split("\n")) {
                if (lines++ > 58) {
                    content.endText();
                    content.close();
                    page = new PDPage(PDRectangle.A4);
                    doc.addPage(page);
                    content = new PDPageContentStream(doc, page);
                    content.beginText();
                    content.setFont(font, 10);
                    content.setLeading(13);
                    content.newLineAtOffset(40, 800);
                    lines = 0;
                }
                // Standard-14 fonts are WinAnsi only; map the few non-Latin-1 characters in fixtures.
                content.showText(line.replace("•", "-").replace("–", "-").replace("’", "'"));
                content.newLine();
            }
            content.endText();
            content.close();
            doc.save(out);
            return out.toByteArray();
        }
    }

    public static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : text.split("\n")) {
                doc.createParagraph().createRun().setText(line);
            }
            doc.write(out);
            return out.toByteArray();
        }
    }
}
