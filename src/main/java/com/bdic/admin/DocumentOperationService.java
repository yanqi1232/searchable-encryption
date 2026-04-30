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
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
    /** 关键词元数据中用于标记用户描述行的前缀，值本身会用 Base64 存放。 */
    public static final String DESCRIPTION_METADATA_PREFIX = "__description_b64__:";

    /** 允许自动抽取文本关键词的最大文件大小。 */
    private final long maxAutomaticTextKeywordBytes;

    /**
     * 创建文档操作服务。
     *
     * @param maxAutomaticTextKeywordBytes 文件不超过该大小时才尝试自动抽取正文关键词。
     */
    public DocumentOperationService(long maxAutomaticTextKeywordBytes) {
        this.maxAutomaticTextKeywordBytes = maxAutomaticTextKeywordBytes;
    }

    /**
     * 将输入框中的纯文本包装成统一上传内容。
     */
    public UploadContent resolveTextUploadContent(String docId, String content) {
        // 纯文本上传没有真实文件名，因此使用 docId 派生一个 txt 文件名。
        byte[] originalContent = content.getBytes(StandardCharsets.UTF_8);
        return new UploadContent(originalContent, docId + ".txt", "text/plain", "text", originalContent.length, content, true);
    }

    /**
     * 读取本地文件，识别文件类型，并在合适时抽取可搜索文本。
     */
    public UploadContent resolveFileUploadContent(Path path) throws IOException {
        long fileSize = Files.size(path);
        byte[] originalContent = Files.readAllBytes(path);
        String fileName = path.getFileName().toString();
        String mimeType = detectMimeType(path);
        String mediaType = inferMediaType(fileName, mimeType);
        boolean automaticTextKeywordsEnabled = shouldUseAutomaticTextKeywords(mediaType, fileSize);
        // 对文本、PDF、Word 等可读文档尝试抽取正文，图片/音视频/大文件则跳过。
        String extractedText = automaticTextKeywordsEnabled
                ? DocumentTextExtractor.extract(path, mimeType, mediaType)
                : "";
        return new UploadContent(originalContent, fileName, mimeType, mediaType, fileSize, extractedText, automaticTextKeywordsEnabled);
    }

    /**
     * 根据上传内容生成可发送到服务端的加密文档实体。
     */
    public EncryptedData buildEncryptedData(String docId, UploadContent uploadContent, String descriptionInput, SecretKey desKey, PublicKey peksPublicKey) throws Exception {
        List<String> keywords = resolveKeywords(descriptionInput, uploadContent);
        if (keywords.isEmpty()) {
            throw new IllegalArgumentException("No searchable keywords were found for " + uploadContent.fileName());
        }

        // 正文用 DES 加密，关键词用 PEKS 公钥转成可搜索密文。
        byte[] encryptedContent = DESUtil.encrypt(uploadContent.originalContent(), desKey);
        List<byte[]> peksCiphertexts = new ArrayList<>();
        for (String keyword : buildSearchableTokens(keywords)) {
            peksCiphertexts.add(PEKSUtil.encrypt(peksPublicKey, keyword));
        }

        return new EncryptedData(
                docId,
                uploadContent.fileName(),
                uploadContent.mimeType(),
                uploadContent.mediaType(),
                uploadContent.fileSize(),
                encryptKeywordMetadata(keywords, descriptionInput, desKey),
                encryptedContent,
                peksCiphertexts
        );
    }

    /**
     * 重新生成某个文档的关键词密文索引。
     *
     * <p>优先从加密关键词元数据中恢复原关键词；旧文档缺少元数据时会请用户手动输入。</p>
     */
    public EncryptedData rebuildIndex(EncryptedData data, SecretKey desKey, PublicKey peksPublicKey, Component parent) throws Exception {
        String[] originalKeywords = resolveOriginalKeywords(data, desKey, parent);
        if (originalKeywords == null || originalKeywords.length == 0) {
            throw new IllegalStateException("No keywords available for reindexing.");
        }

        List<byte[]> rebuiltCiphertexts = new ArrayList<>();
        // 重建时沿用上传时的 token 扩展规则，保证前缀搜索能力一致。
        for (String token : buildSearchableTokens(originalKeywords)) {
            rebuiltCiphertexts.add(PEKSUtil.encrypt(peksPublicKey, token));
        }
        data.setPeksCiphertexts(rebuiltCiphertexts);
        data.setEncryptedKeywordMetadata(encryptKeywordMetadata(originalKeywords, desKey));
        return data;
    }

    /**
     * 收集文件夹下所有普通文件，并按路径排序，供批量上传使用。
     */
    public List<Path> collectFiles(Path folder) throws IOException {
        try (var stream = Files.walk(folder)) {
            return stream.filter(Files::isRegularFile)
                    .sorted(Comparator.naturalOrder())
                    .collect(Collectors.toList());
        }
    }

    /**
     * 返回下载时建议使用的文件名。
     */
    public String defaultFileName(EncryptedData data) {
        return data.getFileName() == null || data.getFileName().isBlank() ? data.getDocId() : data.getFileName();
    }

    /**
     * 在目标目录中生成不覆盖现有文件的路径。
     */
    public Path resolveUniqueChildPath(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }

        // 将 "a.txt" 拆成 "a" 和 ".txt"，冲突时生成 "a (1).txt"。
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

    /**
     * 提取最适合展示给用户的异常信息。
     */
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

    /**
     * 判断加密文档是否可以当作文本预览。
     */
    public boolean isTextDocument(EncryptedData data) {
        if (data.getMediaType() == null || data.getMediaType().isBlank()) {
            return true;
        }
        return isTextDocument(data.getMediaType());
    }

    /**
     * 判断媒体分类是否属于文本。
     */
    public boolean isTextDocument(String mediaType) {
        if (mediaType == null || mediaType.isBlank()) {
            return true;
        }
        return "text".equalsIgnoreCase(mediaType);
    }

    /**
     * 综合用户描述、文件名和可抽取正文生成去重后的关键词集合。
     */
    private List<String> resolveKeywords(String descriptionInput, UploadContent uploadContent) {
        Set<String> keywords = new LinkedHashSet<>();
        // 用户描述权重最高，随后补充文件名，最后补充正文自动抽取结果。
        keywords.addAll(KeywordExtractor.extractWords(descriptionInput));
        keywords.addAll(KeywordExtractor.extractFileNameKeywords(uploadContent.fileName()));
        if (uploadContent.automaticTextKeywordsEnabled() && uploadContent.extractedText() != null && !uploadContent.extractedText().isBlank()) {
            keywords.addAll(KeywordExtractor.extractWords(uploadContent.extractedText()));
        } else if (uploadContent.automaticTextKeywordsEnabled() && isTextDocument(uploadContent.mediaType())) {
            // 文本抽取器没有返回内容时，纯文本文件可以直接按 UTF-8 回退读取。
            String text = new String(uploadContent.originalContent(), StandardCharsets.UTF_8);
            keywords.addAll(KeywordExtractor.extractWords(text));
        }
        return new ArrayList<>(keywords);
    }

    /**
     * 判断是否允许对文件执行自动文本关键词抽取。
     */
    private boolean shouldUseAutomaticTextKeywords(String mediaType, long fileSize) {
        return fileSize <= maxAutomaticTextKeywordBytes && supportsAutomaticTextKeywords(mediaType);
    }

    /**
     * 判断媒体分类是否支持正文关键词抽取。
     */
    private boolean supportsAutomaticTextKeywords(String mediaType) {
        return isTextDocument(mediaType) || "document".equalsIgnoreCase(mediaType);
    }

    /**
     * 调用系统能力识别 MIME 类型，失败时按文件名兜底。
     */
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

    /**
     * 把 MIME 类型和扩展名归并成界面使用的粗粒度媒体分类。
     */
    private String inferMediaType(String fileName, String mimeType) {
        String normalizedMimeType = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String normalizedName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);

        // 优先按 MIME 主类型判断，MIME 缺失或不准确时再参考扩展名。
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

    /**
     * 在系统 MIME 探测失败时，按少量常见扩展名给出兜底类型。
     */
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

    /**
     * 兼容数组形式关键词输入，内部转为列表处理。
     */
    private List<String> buildSearchableTokens(String[] keywords) {
        return buildSearchableTokens(Arrays.asList(keywords));
    }

    /**
     * 为关键词集合生成真正进入 PEKS 索引的 token。
     *
     * <p>除完整关键词外，还加入长度从 2 开始的前缀，支持用户输入前缀搜索。</p>
     */
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
            // 例如 searchable 会补充 se、sea、sear...，让前缀陷门也能命中。
            for (int i = 2; i < keyword.length(); i++) {
                tokens.add(keyword.substring(0, i));
            }
        }
        return new ArrayList<>(tokens);
    }

    /**
     * 加密关键词元数据的兼容入口，不携带用户描述。
     */
    private byte[] encryptKeywordMetadata(String[] keywords, SecretKey desKey) throws Exception {
        return encryptKeywordMetadata(Arrays.asList(keywords), null, desKey);
    }

    /**
     * 将关键词和可选描述信息写成文本后用 DES 加密保存。
     */
    private byte[] encryptKeywordMetadata(List<String> keywords, String descriptionInput, SecretKey desKey) throws Exception {
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

        List<String> metadataLines = new ArrayList<>();
        String normalizedDescription = descriptionInput == null ? "" : descriptionInput.trim();
        if (!normalizedDescription.isEmpty()) {
            // 描述可能包含换行或特殊字符，先 Base64 编码再放入按行存储的元数据。
            String encodedDescription = Base64.getEncoder().encodeToString(normalizedDescription.getBytes(StandardCharsets.UTF_8));
            metadataLines.add(DESCRIPTION_METADATA_PREFIX + encodedDescription);
        }
        metadataLines.addAll(normalizedKeywords);

        // 元数据整体加密后保存，服务端无法读取用户描述或明文关键词。
        String joinedMetadata = String.join("\n", metadataLines);
        return DESUtil.encrypt(joinedMetadata.getBytes(StandardCharsets.UTF_8), desKey);
    }

    /**
     * 恢复重建索引所需的原始关键词。
     */
    private String[] resolveOriginalKeywords(EncryptedData data, SecretKey desKey, Component parent) throws Exception {
        byte[] encryptedKeywordMetadata = data.getEncryptedKeywordMetadata();
        if (encryptedKeywordMetadata != null && encryptedKeywordMetadata.length > 0) {
            // 新文档会把关键词元数据加密保存，重建时直接解密并过滤掉描述行。
            String restoredKeywords = new String(DESUtil.decrypt(encryptedKeywordMetadata, desKey), StandardCharsets.UTF_8);
            return Arrays.stream(restoredKeywords.split("\\R"))
                    .map(String::trim)
                    .filter(keyword -> !keyword.isEmpty() && !keyword.startsWith(DESCRIPTION_METADATA_PREFIX))
                    .toArray(String[]::new);
        }

        // 旧文档没有保存关键词元数据，只能让用户手动输入原始关键词。
        String keywordsInput = requestLegacyKeywords(parent);
        if (keywordsInput == null || keywordsInput.isBlank()) {
            return null;
        }
        return Arrays.stream(keywordsInput.split(","))
                .map(String::trim)
                .filter(keyword -> !keyword.isEmpty())
                .toArray(String[]::new);
    }

    /**
     * 旧文档需要人工补关键词时，确保输入框始终在 Swing 事件线程中显示。
     */
    private String requestLegacyKeywords(Component parent) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return showLegacyKeywordInput(parent);
        }

        final String[] input = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> input[0] = showLegacyKeywordInput(parent));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
        return input[0];
    }

    /**
     * 显示旧文档关键词输入框。
     */
    private String showLegacyKeywordInput(Component parent) {
        return JOptionPane.showInputDialog(
                parent,
                "This legacy document has no stored keyword metadata.\nPlease enter the original keywords separated by commas:",
                "Rebuild Index",
                JOptionPane.PLAIN_MESSAGE
        );
    }

    /**
     * 上传前的统一内容描述。
     *
     * @param originalContent 原始明文字节。
     * @param fileName 原始文件名或自动生成的文本文件名。
     * @param mimeType 文件 MIME 类型。
     * @param mediaType 简化媒体分类。
     * @param fileSize 原始文件大小。
     * @param extractedText 自动抽取出的正文文本，可能为空。
     * @param automaticTextKeywordsEnabled 是否允许基于正文自动生成关键词。
     */
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
