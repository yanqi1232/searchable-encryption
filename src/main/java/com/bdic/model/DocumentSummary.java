package com.bdic.model;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 文档摘要信息。
 *
 * <p>用于文档列表展示，只包含元数据，不包含加密正文。</p>
 */
public class DocumentSummary implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String docId;
    private final String fileName;
    private final String mediaType;
    private final long fileSize;
    private final int keywordCount;
    private final LocalDateTime createdAt;

    public DocumentSummary(String docId, String fileName, String mediaType, long fileSize, int keywordCount, LocalDateTime createdAt) {
        this.docId = docId;
        this.fileName = fileName;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.keywordCount = keywordCount;
        this.createdAt = createdAt;
    }

    public String getDocId() {
        return docId;
    }

    public int getKeywordCount() {
        return keywordCount;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
