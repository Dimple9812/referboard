package com.referboard.ingestion.normalize;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;
import org.jsoup.safety.Safelist;

/**
 * Job descriptions arrive as third-party HTML. We keep a sanitised copy (safe to render: no scripts, styles,
 * iframes, event handlers or javascript: links) and a plain-text copy for search, skills and embeddings.
 */
public final class Html {

    private static final Safelist SAFE = Safelist.relaxed()
            .removeTags("img")
            .addAttributes("a", "rel", "target")
            .addProtocols("a", "href", "http", "https", "mailto");

    private static final int MAX_HTML = 100_000;

    private Html() {
    }

    /** Greenhouse double-encodes its HTML (&amp;lt;p&amp;gt;); decode entities first when needed. */
    public static String unescapeIfEncoded(String html) {
        if (html == null) {
            return null;
        }
        return html.contains("&lt;") && !html.contains("<") ? Parser.unescapeEntities(html, false) : html;
    }

    public static String sanitize(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);
        String clean = Jsoup.clean(html, "", SAFE, out);
        Document doc = Jsoup.parseBodyFragment(clean);
        for (Element a : doc.select("a[href]")) {
            a.attr("rel", "noopener noreferrer nofollow");
            a.attr("target", "_blank");
        }
        String result = doc.body().html();
        return result.length() > MAX_HTML ? result.substring(0, MAX_HTML) : result;
    }

    /** Readable text with paragraph and list-item breaks preserved. */
    public static String toText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        Document doc = Jsoup.parse(html);
        doc.outputSettings().prettyPrint(false);
        doc.select("br").before("\\n");
        doc.select("p, li, h1, h2, h3, h4, h5, h6, div, tr").before("\\n");
        doc.select("li").prepend("• ");
        String text = doc.text().replace("\\n", "\n");
        return text.replaceAll("[ \\t\\u00A0]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
