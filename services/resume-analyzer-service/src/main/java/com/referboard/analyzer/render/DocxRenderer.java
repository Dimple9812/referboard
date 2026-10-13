package com.referboard.analyzer.render;

import com.referboard.analyzer.model.ResumeContent;
import org.apache.poi.xwpf.usermodel.Borders;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;

/**
 * ATS-friendly DOCX: one column, real text (no tables, text boxes, headers or images), standard section headings,
 * plain bullets. Apache POI.
 */
public final class DocxRenderer {

    private static final String FONT = "Calibri";

    private DocxRenderer() {
    }

    public static byte[] render(ResumeContent c) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            margins(doc);
            if (c.fullName() != null) {
                XWPFParagraph p = para(doc, 0, 40);
                p.setAlignment(ParagraphAlignment.CENTER);
                run(p, c.fullName(), 18, true);
            }
            String contact = ResumeFormat.contactLine(c);
            if (!contact.isBlank()) {
                XWPFParagraph p = para(doc, 0, 40);
                p.setAlignment(ParagraphAlignment.CENTER);
                run(p, contact, 9.5, false);
            }
            if (c.headline() != null && !c.headline().isBlank()) {
                XWPFParagraph p = para(doc, 0, 80);
                p.setAlignment(ParagraphAlignment.CENTER);
                run(p, c.headline(), 10.5, false).setItalic(true);
            }
            if (c.summary() != null && !c.summary().isBlank()) {
                heading(doc, "Summary");
                run(para(doc, 0, 60), c.summary(), 10.5, false);
            }
            if (!c.skills().isEmpty()) {
                heading(doc, "Skills");
                run(para(doc, 0, 60), String.join(", ", c.skills()), 10.5, false);
            }
            if (!c.experiences().isEmpty()) {
                heading(doc, "Experience");
                for (ResumeContent.Experience e : c.experiences()) {
                    XWPFParagraph p = para(doc, 80, 20);
                    run(p, ResumeFormat.roleLine(e), 10.5, true);
                    String dates = ResumeFormat.dates(e);
                    if (dates != null) {
                        run(p, "    " + dates, 10, false);
                    }
                    for (String b : e.bullets()) {
                        XWPFParagraph bp = para(doc, 0, 20);
                        bp.setIndentationLeft(360);
                        bp.setIndentationHanging(220);
                        run(bp, "•  " + b, 10.5, false);
                    }
                }
            }
            if (!c.educations().isEmpty()) {
                heading(doc, "Education");
                c.educations().forEach(ed -> run(para(doc, 0, 40), ResumeFormat.education(ed), 10.5, false));
            }
            if (!c.certifications().isEmpty()) {
                heading(doc, "Certifications");
                c.certifications().forEach(cert -> run(para(doc, 0, 20), "•  " + cert, 10.5, false));
            }
            doc.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void heading(XWPFDocument doc, String text) {
        XWPFParagraph p = para(doc, 200, 80);
        p.setBorderBottom(Borders.SINGLE);
        run(p, text.toUpperCase(java.util.Locale.ROOT), 11, true);
    }

    private static XWPFParagraph para(XWPFDocument doc, int before, int after) {
        XWPFParagraph p = doc.createParagraph();
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, String text, double size, boolean bold) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        r.setBold(bold);
        r.setText(text);
        return r;
    }

    private static void margins(XWPFDocument doc) {
        CTSectPr sect = doc.getDocument().getBody().isSetSectPr() ? doc.getDocument().getBody().getSectPr()
                : doc.getDocument().getBody().addNewSectPr();
        CTPageMar mar = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        BigInteger m = BigInteger.valueOf(1008);   // 0.7 inch
        mar.setTop(m);
        mar.setBottom(m);
        mar.setLeft(m);
        mar.setRight(m);
    }
}
