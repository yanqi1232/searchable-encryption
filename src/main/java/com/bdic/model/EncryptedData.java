package com.bdic.model;

import java.io.Serializable;
import java.util.List;

/**
 * 加密文档实体。
 *
 * <p>客户端上传时会填充文档元数据、DES 加密后的正文、加密后的关键词元数据，
 * 以及用于服务端搜索的关键词密文集合。</p>
 */
public class EncryptedData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String docId;
    private String fileName;
    private String mimeType;
    private String mediaType;
    private long fileSize;
    private byte[] encryptedKeywordMetadata;
    private byte[] encryptedContent;
    private List<byte[]> peksCiphertexts;

    public EncryptedData(String docId, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this(docId, docId + ".txt", "text/plain", "text", encryptedContent == null ? 0 : encryptedContent.length, null, encryptedContent, peksCiphertexts);
    }

    public EncryptedData(String docId, String fileName, String mimeType, String mediaType, long fileSize, byte[] encryptedKeywordMetadata, byte[] encryptedContent, List<byte[]> peksCiphertexts) {
        this.docId = docId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.mediaType = mediaType;
        this.fileSize = fileSize;
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
        this.encryptedContent = encryptedContent;
        this.peksCiphertexts = peksCiphertexts;
    }

    public String getDocId() {
        return docId;
    }

    public void setDocId(String docId) {
        this.docId = docId;
    }

    public byte[] getEncryptedContent() {
        return encryptedContent;
    }

    public void setEncryptedContent(byte[] encryptedContent) {
        this.encryptedContent = encryptedContent;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    public long getFileSize() {
        return fileSize;
    }

    public void setFileSize(long fileSize) {
        this.fileSize = fileSize;
    }

    public byte[] getEncryptedKeywordMetadata() {
        return encryptedKeywordMetadata;
    }

    public void setEncryptedKeywordMetadata(byte[] encryptedKeywordMetadata) {
        this.encryptedKeywordMetadata = encryptedKeywordMetadata;
    }

    public List<byte[]> getPeksCiphertexts() {
        return peksCiphertexts;
    }

    public void setPeksCiphertexts(List<byte[]> peksCiphertexts) {
        this.peksCiphertexts = peksCiphertexts;
    }
}
