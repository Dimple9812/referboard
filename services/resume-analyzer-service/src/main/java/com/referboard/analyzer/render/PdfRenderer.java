package com.referboard.analyzer.render;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.referboard.analyzer.model.ResumeContent;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;

/**
 * ATS-friendly PDF with real, selectable text in reading order (OpenPDF). Uses the standard Helvetica font, so
 * text is mapped to its Latin-1 range: typographic punctuation is replaced, accents kept, other scripts dropped.
 */
public final class PdfRenderer {

    /** Common characters outside Windows-1252 (the standard fonts' encoding) with readable substitutes. */
    private static final Map<String, String> REPLACEMENTS = Map.of("₹", "INR ", "→", "->", "←", "<-", "✓", "-",
            "−", "-", "≥", ">=", "≤", "<=");
    private static final java.nio.charset.CharsetEncoder WINANSI =
            java.nio.charset.Charset.forName("windows-1252").newEncoder();

    private PdfRenderer() {
    }

    public static byte[] render(ResumeContent c) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 46, 46);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPdfVersion(PdfWriter.VERSION_1_7);
            doc.addTitle(clean(c.fullName() == null ? "Resume" : c.fullName() + " - Resume"));
            doc.open();
            Font name = font(BaseFont.HELVETICA_BOLD, 18);
            Font small = font(BaseFont.HELVETICA, 9.5f);
            Font body = font(BaseFont.HELVETICA, 10.5f);
            Font bold = font(BaseFont.HELVETICA_BOLD, 10.5f);
            Font italic = font(BaseFont.HELVETICA_OBLIQUE, 10.5f);

            if (c.fullName() != null) {
                doc.add(centered(c.fullName(), name, 2));
            }
            String contact = ResumeFormat.contactLine(c);
            if (!contact.isBlank()) {
                doc.add(centered(contact, small, 2));
            }
            if (c.headline() != null && !c.headline().isBlank()) {
                doc.add(centered(c.headline(), italic, 4));
            }
            if (c.summary() != null && !c.summary().isBlank()) {
                heading(doc, "Summary");
                doc.add(para(c.summary(), body, 0, 3));
            }
            if (!c.skills().isEmpty()) {
                heading(doc, "Skills");
                doc.add(para(String.join(", ", c.skills()), body, 0, 3));
            }
            if (!c.experiences().isEmpty()) {
                heading(doc, "Experience");
                for (ResumeContent.Experience e : c.experiences()) {
                    Paragraph role = new Paragraph();
                    role.setSpacingBefore(5);
                    role.add(new Chunk(clean(ResumeFormat.roleLine(e)), bold));
                    String dates = ResumeFormat.dates(e);
                    if (dates != null) {
                        role.add(new Chunk("    " + clean(dates), font(BaseFont.HELVETICA, 10)));
                    }
                    doc.add(role);
                    for (String b : e.bullets()) {
                        Paragraph bp = para("•  " + b, body, 1, 1);
                        bp.setIndentationLeft(14);
                        bp.setFirstLineIndent(-8);
                        doc.add(bp);
                    }
                }
            }
            if (!c.educations().isEmpty()) {
                heading(doc, "Education");
                for (ResumeContent.Education ed : c.educations()) {
                    doc.add(para(ResumeFormat.education(ed), body, 1, 1));
                }
            }
            if (!c.certifications().isEmpty()) {
                heading(doc, "Certifications");
                for (String cert : c.certifications()) {
                    doc.add(para("•  " + cert, body, 1, 1));
                }
            }
            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    private static void heading(Document doc, String text) throws DocumentException {
        Paragraph p = para(text.toUpperCase(Locale.ROOT), font(BaseFont.HELVETICA_BOLD, 11), 10, 0);
        doc.add(p);
        LineSeparator line = new LineSeparator(0.6f, 100, Color.GRAY, Element.ALIGN_CENTER, -2);
        doc.add(new Chunk(line));
        doc.add(para(" ", font(BaseFont.HELVETICA, 3), 0, 0));
    }

    private static Paragraph centered(String text, Font f, float after) {
        Paragraph p = para(text, f, 0, after);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
    }

    private static Paragraph para(String text, Font f, float before, float after) {
        Paragraph p = new Paragraph(clean(text), f);
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        p.setLeading(f.getSize() * 1.3f);
        return p;
    }

    private static Font font(String name, float size) {
        try {
            return new Font(BaseFont.createFont(name, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED), size);
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Maps text into WinAnsi (Windows-1252): bullets, dashes, curly quotes and Latin accents are kept, a few common
     * symbols are substituted, anything else (other scripts, emoji) is dropped.
     */
    static synchronized String clean(String s) {
        if (s == null) {
            return "";
        }
        String t = s;
        for (Map.Entry<String, String> e : REPLACEMENTS.entrySet()) {
            t = t.replace(e.getKey(), e.getValue());
        }
        t = Normalizer.normalize(t, Normalizer.Form.NFC);
        StringBuilder sb = new StringBuilder(t.length());
        for (char ch : t.toCharArray()) {
            if (WINANSI.canEncode(ch)) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}
