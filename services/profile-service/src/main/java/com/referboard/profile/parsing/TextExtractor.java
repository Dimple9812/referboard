package com.referboard.profile.parsing;

import com.referboard.common.web.ApiException;
import org.apache.tika.Tika;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;

/**
 * Detects the real file type from its bytes (the browser's Content-Type and the file extension are not trusted)
 * and extracts plain text with Apache Tika.
 */
@Component
public class TextExtractor {

    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String DOC = "application/msword";
    public static final String TXT = "text/plain";

    private static final Map<String, String> ALLOWED = Map.of(PDF, "PDF", DOCX, "DOCX", DOC, "DOC", TXT, "TXT");
    private static final int MAX_CHARS = 200_000;

    private final Tika tika = new Tika();

    public record Extracted(String mediaType, String text) {
    }

    /** @throws ApiException 415 for unsupported types, 422 when no text can be extracted (e.g. scanned image) */
    public String detect(byte[] content, String fileName) {
        String mediaType = tika.detect(content, fileName);
        if (!ALLOWED.containsKey(mediaType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "Upload a PDF, DOCX, DOC or TXT file");
        }
        return mediaType;
    }

    public Extracted extract(byte[] content, String fileName) {
        String mediaType = detect(content, fileName);
        BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName);
        try (InputStream in = new ByteArrayInputStream(content)) {
            new AutoDetectParser().parse(in, handler, metadata, new ParseContext());
        } catch (Exception e) {
            if (!WriteLimitReachedException.isWriteLimitReached(e)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNREADABLE_FILE",
                        "We couldn't read this file. It may be corrupted or password-protected.");
            }
        }
        String text = normalize(handler.toString());
        if (text.replaceAll("\\s", "").length() < 50) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_TEXT_FOUND",
                    "No text found in this file. If it's a scanned image, export your resume as a text-based PDF.");
        }
        return new Extracted(mediaType, text);
    }

    static String normalize(String raw) {
        return raw
                .replace(' ', ' ')
                .replaceAll("[\\t\\x0B\\f\\r]+", " ")
                .replaceAll(" {2,}", " ")
                .replaceAll("(?m)^ +| +$", "")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
