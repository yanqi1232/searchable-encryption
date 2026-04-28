package com.bdic.text;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 文档文本抽取器。
 *
 * <p>用于从可读文本、PDF 和 Word 文档中抽取纯文本，
 * 以便后续自动生成可搜索关键词。</p>
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {
    }

    public static String extract(Path path, String mimeType, String mediaType) {
        if (path == null || !Files.exists(path)) {
            return "";
        }

        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase(Locale.ROOT);

        try {
            if (isPdf(normalizedMimeType, fileName)) {
                return extractPdf(path);
            }
            if (isWord(normalizedMimeType, fileName)) {
                return extractWord(path, fileName);
            }
            if (isPlainTextLike(mediaType, normalizedMimeType, fileName)) {
                return Files.readString(path, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "";
        }

        return "";
    }

    private static boolean isPdf(String mimeType, String fileName) {
        return "application/pdf".equals(mimeType) || fileName.endsWith(".pdf");
    }

    private static boolean isWord(String mimeType, String fileName) {
        return "application/msword".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType)
                || fileName.endsWith(".doc")
                || fileName.endsWith(".docx");
    }

    /**
     * JSON 文件在本项目里按可读文本处理，便于抽取关键词并在搜索结果中预览。
     */
    private static boolean isPlainTextLike(String mediaType, String mimeType, String fileName) {
        return "text".equalsIgnoreCase(mediaType)
                || mimeType.startsWith("text/")
                || "application/json".equals(mimeType)
                || mimeType.endsWith("+json")
                || fileName.endsWith(".json");
    }

    private static String extractPdf(Path path) throws IOException {
        try (PDDocument document = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        }
    }

    private static String extractWord(Path path, String fileName) throws IOException {
        if (fileName.endsWith(".docx")) {
            try (InputStream inputStream = Files.newInputStream(path);
                 XWPFDocument document = new XWPFDocument(inputStream);
                 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
                return extractor.getText();
            }
        }

        try (InputStream inputStream = Files.newInputStream(path);
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }
}
