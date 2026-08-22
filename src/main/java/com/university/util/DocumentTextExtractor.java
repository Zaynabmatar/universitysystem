package com.university.util;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts plain text from a PDF, Word ({@code .docx}), or plain-text file attached to the
 * University AI Assistant chat panel, so {@link com.university.service.GeneralAIService} can send
 * that text to Gemini alongside the user's question. Read-only — nothing here writes to disk or
 * touches the database.
 *
 * <p>PDF extraction reuses OpenPDF (already a dependency for {@link PdfExporter}'s transcript
 * export) rather than pulling in a new library. Old binary {@code .doc} files are not supported —
 * only the zip/XML-based {@code .docx} format, which needs no extra dependency to parse.</p>
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {
    }

    public static String extractText(File file) throws IOException {
        String name = file.getName().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return extractPdf(file);
        }
        if (name.endsWith(".docx")) {
            return extractDocx(file);
        }
        if (name.endsWith(".txt")) {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        }
        throw new IOException("Unsupported file type: " + file.getName());
    }

    private static String extractPdf(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PdfReader reader = new PdfReader(file.getAbsolutePath())) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            int pages = reader.getNumberOfPages();
            for (int page = 1; page <= pages; page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
        }
        return sb.toString();
    }

    /** A .docx is a zip archive; its visible text lives in word/document.xml as a run of <w:t> elements. */
    private static String extractDocx(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry("word/document.xml");
            if (entry == null) {
                throw new IOException("Not a valid Word document: " + file.getName());
            }
            String xml;
            try (InputStream in = zip.getInputStream(entry)) {
                xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            return stripDocxXml(xml);
        }
    }

    /**
     * Paragraph/tab tags become whitespace so words from different paragraphs don't run together;
     * every other tag (formatting, styles, run properties, etc.) is simply dropped since only the
     * visible text is wanted.
     */
    private static String stripDocxXml(String xml) {
        String withBreaks = xml
                .replaceAll("<w:p[ />]", "\n$0")
                .replaceAll("<w:tab/>", "\t");
        return withBreaks.replaceAll("<[^>]+>", "").trim();
    }
}
