package com.referboard.analyzer.render;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

/**
 * A cover letter as a plain business letter: the sender's name and contact line, the date, then the body exactly
 * as the user last edited it (paragraphs split on blank lines, line breaks kept inside a paragraph).
 */
public final class LetterRenderer {

    public record Letter(String senderName, String contactLine, String date, String body) {
    }

    private LetterRenderer() {
    }

    static List<String> paragraphs(String body) {
        return Arrays.stream(body.strip().split("\\n\\s*\\n")).map(String::strip).filter(p -> !p.isEmpty()).toList();
    }

    public static byte[] docx(Letter l) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                    : doc.getDocument().getBody().addNewSectPr();
            CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
            BigInteger m = BigInteger.valueOf(1296);   // 0.9 inch
            mar.setTop(m);
            mar.setBottom(m);
            mar.setLeft(m);
            mar.setRight(m);
            if (l.senderName() != null && !l.senderName().isBlank()) {
                run(para(doc, 0, 20), l.senderName(), 14, true);
            }
            if (l.contactLine() != null && !l.contactLine().isBlank()) {
                run(para(doc, 0, 240), l.contactLine(), 9.5, false);
            }
            if (l.date() != null) {
                run(para(doc, 0, 240), l.date(), 11, false);
            }
            for (String p : paragraphs(l.body())) {
                XWPFParagraph para = para(doc, 0, 200);
                String[] lines = p.split("\\n");
                for (int i = 0; i < lines.length; i++) {
                    XWPFRun r = run(para, lines[i].strip(), 11, false);
                    if (i < lines.length - 1) {
                        r.addBreak();
                    }
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] pdf(Letter l) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 65, 65, 60, 60);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPdfVersion(PdfWriter.VERSION_1_7);
            doc.addTitle(PdfRenderer.clean((l.senderName() == null ? "" : l.senderName() + " - ") + "Cover letter"));
            doc.open();
            Font name = font(BaseFont.HELVETICA_BOLD, 14);
            Font small = font(BaseFont.HELVETICA, 9.5f);
            Font body = font(BaseFont.HELVETICA, 11);
            if (l.senderName() != null && !l.senderName().isBlank()) {
                doc.add(para(l.senderName(), name, 2));
            }
            if (l.contactLine() != null && !l.contactLine().isBlank()) {
                doc.add(para(l.contactLine(), small, 16));
            }
            if (l.date() != null) {
                doc.add(para(l.date(), body, 16));
            }
            for (String p : paragraphs(l.body())) {
                doc.add(para(p, body, 10));
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    private static Paragraph para(String text, Font f, float after) {
        Paragraph p = new Paragraph(PdfRenderer.clean(text), f);   // \n inside a paragraph stays a line break
        p.setSpacingAfter(after);
        p.setLeading(f.getSize() * 1.4f);
        return p;
    }

    private static Font font(String name, float size) {
        try {
            return new Font(BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED), size);
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static XWPFParagraph para(XWPFDocument doc, int before, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, String text, double size, boolean bold) {
        XWPFRun r = p.createRun();
        r.setFontFamily("Calibri");
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(text);
        return r;
    }
}
