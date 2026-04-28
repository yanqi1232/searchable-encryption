package com.bdic.admin;

import com.bdic.crypto.DESUtil;
import com.bdic.crypto.PEKSUtil;
import com.bdic.model.EncryptedData;
import com.bdic.text.DocumentTextExtractor;
import com.bdic.text.KeywordExtractor;

import javax.crypto.SecretKey;
import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 负责文档上传、索引重建、关键词处理等核心逻辑。
 */
public class DocumentOperationService {

    private final long maxAutomaticTextKeywordBytes;

    public DocumentOperationService(long maxAutomaticTextKeywordBytes) {
        this.maxAutomaticTextKeywordBytes = maxAutomaticTextKeywordBytes;
    }

    public UploadContent resolveTextUploadContent(String docId, String content) {
        byte[] originalContent = content.getBytes(StandardCharsets.UTF_8);
        return new UploadContent(originalContent, docId + ".txt", "text/plain", "text", originalContent.length, content, true);
    }

    public UploadContent resolveFileUploadContent(Path path) throws IOException {
        long fileSize = Files.size(path);
        byte[] originalContent = Files.readAllBytes(path);
        String fileName = path.getFileName().toString();
        String mimeType = detectMimeType(path);
        String mediaType = inferMediaType(fileName, mimeType);
        boolean automaticTextKeywordsEnabled = shouldUseAutomaticTextKeywords(mediaType, fileSize);
        String extractedText = automaticTextKeywordsEnabled
                ? DocumentTextExtractor.extract(path, mimeType, mediaType)
                : "";
        return new UploadContent(originalContent, fileName, mimeType, mediaType, fileSize, extractedText, automaticTextKeywordsEnabled);
    }

    public EncryptedData buildEncryptedData(String docId, UploadContent uploadContent, String keywordsInput, SecretKey desKey, SecretKey peksKey) throws Exception {
        List<String> keywords = resolveKeywords(keywordsInput, uploadContent);
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("No searchable keywords were found for " + uploadContent.fileName());
        }

        byte[] encryptedContent = DESUtil.encrypt(uploadContent.originalContent(), desKey);
        List<byte[]> peksCiphertexts = new ArrayList<>();
        for (String keyword : buildSearchableTokens(keywords)) {
            peksCiphertexts.add(PEKSUtil.encrypt(peksKey, keyword));
        }

        return new EncryptedData(
                docId,
                uploadContent.fileName(),
                uploadContent.mimeType(),
                uploadContent.mediaType(),
                uploadContent.fileSize(),
                encryptKeywordMetadata(keywords, desKey),
                encryptedContent,
                peksCiphertexts
        );
    }

    public EncryptedData rebuildIndex(EncryptedData data, SecretKey desKey, SecretKey peksKey, Component parent) throws Exception {
        String[] originalKeywords = resolveOriginalKeywords(data, desKey, parent);
        if (originalKeywords == null || originalKeywords.length == 0) {
            throw new IllegalStateException("No keywords available for reindexing.");
        }

        List<byte[]> rebuiltCiphertexts = new ArrayList<>();
        for (String token : buildSearchableTokens(originalKeywords)) {
            rebuiltCiphertexts.add(PEKSUtil.encrypt(peksKey, token));
        }
        data.setPeksCiphertexts(rebuiltCiphertexts);
        data.setEncryptedKeywordMetadata(encryptKeywordMetadata(originalKeywords, desKey));
        return data;
    }

    public List<Path> collectFiles(Path folder) throws IOException {
        try (var stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    public String defaultFileName(EncryptedData data) {
        return data.getFileName() == null || data.getFileName().isBlank() ? data.getDocId() : data.getFileName();
    }

    public Path resolveUniqueChildPath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        String baseName = fileName;
        String extension = "";
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = fileName.substring(0, dotIndex);
            extension = fileName.substring(dotIndex);
        }

        int counter = 1;
        while (true) {
            Path nextCandidate = directory.resolve(baseName + " (" + counter + ")" + extension);
            if (!Files.exists(nextCandidate)) {
                return nextCandidate;
            }
            counter++;
        }
    }

    public static String describeException(Throwable throwable) {
        Throwable rootCause = throwable;
        while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
            rootCause = rootCause.getCause();
        }
        String message = rootCause.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        message = throwable.getMessage();
        if (message != null && !message.isBlank()) {
            return message;
        }
        return rootCause.getClass().getSimpleName();
    }

    public boolean isTextDocument(EncryptedData data) {
        if (data.getMediaType() == null || data.getMediaType().isBlank()) {
            return true;
        }
        return isTextDocument(data.getMediaType());
    }

    public boolean isTextDocument(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return true;
        }
        return "text".equalsIgnoreCase(mediaType);
    }

    private List<String> resolveKeywords(String keywordsInput, UploadContent uploadContent) {
        Set<String> keywords = new LinkedHashSet<>();
        keywords.addAll(KeywordExtractor.extractCommaSeparated(keywordsInput));
        keywords.addAll(KeywordExtractor.extractFileNameKeywords(uploadContent.fileName()));
        if (uploadContent.automaticTextKeywordsEnabled() && uploadContent.extractedText() != null && !uploadContent.extractedText().isBlank()) {
            keywords.addAll(KeywordExtractor.extractWords(uploadContent.extractedText()));
        } else if (uploadContent.automaticTextKeywordsEnabled() && isTextDocument(uploadContent.mediaType())) {
            String text = new String(uploadContent.originalContent(), StandardCharsets.UTF_8);
            keywords.addAll(KeywordExtractor.extractWords(text));
        }
        return new ArrayList<>(keywords);
    }

    private boolean shouldUseAutomaticTextKeywords(String mediaType, long fileSize) {
        return fileSize <= maxAutomaticTextKeywordBytes && supportsAutomaticTextKeywords(mediaType);
    }

    private boolean supportsAutomaticTextKeywords(String mediaType) {
        return isTextDocument(mediaType) || "document".equalsIgnoreCase(mediaType);
    }

    private String detectMimeType(Path path) {
        try {
            String detectedMimeType = Files.probeContentType(path);
            if (detectedMimeType != null && !detectedMimeType.isBlank()) {
                return detectedMimeType;
            }
            return guessMimeTypeFromFileName(path.getFileName() == null ? null : path.getFileName().toString());
        } catch (IOException e) {
            return guessMimeTypeFromFileName(path.getFileName() == null ? null : path.getFileName().toString());
        }
    }

    private String inferMediaType(String fileName, String mimeType) {
        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        if (normalizedMimeType.startsWith("image/")) {
            return "image";
        }
        if (normalizedMimeType.startsWith("video/")) {
            return "video";
        }
        if (normalizedMimeType.startsWith("audio/")) {
            return "audio";
        }
        if (normalizedMimeType.startsWith("text/")
                || "application/json".equals(normalizedMimeType)
                || normalizedMimeType.endsWith("+json")
                || normalizedName.endsWith(".json")) {
            return "text";
        }
        if (normalizedName.endsWith(".pdf") || normalizedName.endsWith(".doc") || normalizedName.endsWith(".docx")
                || normalizedName.endsWith(".xls") || normalizedName.endsWith(".xlsx") || normalizedName.endsWith(".ppt")
                || normalizedName.endsWith(".pptx")) {
            return "document";
        }
        return "binary";
    }

    private String guessMimeTypeFromFileName(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String normalizedName = fileName.toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".json")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private List<String> buildSearchableTokens(String[] keywords) {
        return buildSearchableTokens(Arrays.asList(keywords));
    }

    private List<String> buildSearchableTokens(List<String> keywords) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String rawKeyword : keywords) {
            if (rawKeyword == null) {
                continue;
            }

            String keyword = rawKeyword.trim().toLowerCase(Locale.ROOT);
            if (keyword.isEmpty()) {
                continue;
            }

            tokens.add(keyword);
            if (keyword.length() <= 2) {
                continue;
            }
            for (int i = 2; i < keyword.length(); i++) {
                tokens.add(keyword.substring(0, i));
            }
        }
        return new ArrayList<>(tokens);
    }

    private byte[] encryptKeywordMetadata(String[] keywords, SecretKey desKey) throws Exception {
        return encryptKeywordMetadata(Arrays.asList(keywords), desKey);
    }

    private byte[] encryptKeywordMetadata(List<String> keywords, SecretKey desKey) throws Exception {
        List<String> normalizedKeywords = new ArrayList<>();
        for (String rawKeyword : keywords) {
            if (rawKeyword == null) {
                continue;
            }
            String keyword = rawKeyword.trim().toLowerCase(Locale.ROOT);
            if (!keyword.isEmpty()) {
                normalizedKeywords.add(keyword);
            }
        }

        if (normalizedKeywords.isEmpty()) {
            return null;
        }

        String joinedKeywords = String.join("\n", normalizedKeywords);
        return DESUtil.encrypt(joinedKeywords.getBytes(StandardCharsets.UTF_8), desKey);
    }

    private String[] resolveOriginalKeywords(EncryptedData data, SecretKey desKey, Component parent) throws Exception {
        byte[] encryptedKeywordMetadata = data.getEncryptedKeywordMetadata();
        if (encryptedKeywordMetadata != null && encryptedKeywordMetadata.length > 0) {
            String restoredKeywords = new String(DESUtil.decrypt(encryptedKeywordMetadata, desKey), StandardCharsets.UTF_8);
            return Arrays.stream(restoredKeywords.split("\\R"))
                    .map(String::trim)
                    .filter(keyword -> !keyword.isEmpty())
                    .toArray(String[]::new);
        }

        String keywordsInput = JOptionPane.showInputDialog(
                parent,
                "This legacy document has no stored keyword metadata.\nPlease enter the original keywords separated by commas:",
                "Rebuild Index",
                JOptionPane.PLAIN_MESSAGE
        );
        if (keywordsInput == null || keywordsInput.isBlank()) {
            return null;
        }
        return Arrays.stream(keywordsInput.split(","))
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .toArray(String[]::new);
    }

    public record UploadContent(
            byte[] originalContent,
            String fileName,
            String mimeType,
            String mediaType,
            long fileSize,
            String extractedText,
            boolean automaticTextKeywordsEnabled
    ) {
    }
}
