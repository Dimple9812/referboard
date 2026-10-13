package com.referboard.analyzer.jd;

import com.referboard.common.web.ApiException;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a JD (pasted text, uploaded PDF/DOCX/TXT, or job-service HTML) into normalised plain text with one line per
 * paragraph or list item ({@code "- "} prefix), which the heuristic parser relies on for sections and bullets.
 */
public final class JdText {

    private static final Set<String> BLOCKS = Set.of("p", "div", "br", "h1", "h2", "h3", "h4", "h5", "h6", "li", "ul",
            "ol", "tr", "section", "article", "header", "footer");

    private JdText() {
    }

    /** Collapses whitespace inside lines, normalises bullets and drops blank runs. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : raw.replace("\r\n", "\n").replace('\r', '\n').split("\n")) {
            String l = line.replace(' ', ' ').replaceAll("[\\t\\x0B\\f ]+", " ").trim();
            l = l.replaceFirst("^[•●▪◦‣∙·*]\\s*", "- ").replaceFirst("^-(?=\\S)", "- ");
            if (!l.isEmpty()) {
                out.append(l).append('\n');
            }
        }
        return out.toString().trim();
    }

    public static String fromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        StringBuilder sb = new StringBuilder();
        walk(doc.body(), sb);
        return normalize(sb.toString());
    }

    private static void walk(Node node, StringBuilder sb) {
        for (Node child : node.childNodes()) {
            if (child instanceof TextNode t) {
                sb.append(t.text());
            } else if (child instanceof Element e) {
                String tag = e.tagName().toLowerCase(Locale.ROOT);
                boolean block = BLOCKS.contains(tag);
                if (block) {
                    sb.append('\n');
                }
                if (tag.equals("li")) {
                    sb.append("- ");
                }
                walk(e, sb);
                if (block) {
                    sb.append('\n');
                }
            }
        }
    }

    /** Extracts text from an uploaded PDF, DOCX or TXT. Scanned (image-only) PDFs give a clear error. */
    public static String fromUpload(InputStream in, String fileName, int maxChars) {
        Metadata metadata = new Metadata();
        if (fileName != null) {
            metadata.set("resourceName", fileName);
        }
        BodyContentHandler handler = new BodyContentHandler(maxChars + 1000);
        try {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (SAXException e) {
            throw ApiException.badRequest("JD_TOO_LONG", "That file has more text than a job description should.");
        } catch (IOException | TikaException e) {
            throw ApiException.badRequest("UNREADABLE_FILE", "Couldn't read that file. Upload a PDF, DOCX or TXT, or "
                    + "paste the job description.");
        }
        String type = metadata.get("Content-Type");
        if (type != null && !(type.startsWith("application/pdf") || type.startsWith("text/plain")
                || type.contains("wordprocessingml") || type.startsWith("application/msword"))) {
            throw ApiException.badRequest("UNSUPPORTED_FILE", "Upload a PDF, DOCX or TXT file.");
        }
        String text = normalize(handler.toString());
        if (text.length() < 50 && type != null && type.startsWith("application/pdf")) {
            throw ApiException.badRequest("SCANNED_PDF", "That PDF has no selectable text (it may be a scan). "
                    + "Paste the job description instead.");
        }
        return text;
    }

    /** Cache key: identical JDs (after normalisation, case-insensitive) share one parse. */
    public static String hash(String normalizedText) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(normalizedText.toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
