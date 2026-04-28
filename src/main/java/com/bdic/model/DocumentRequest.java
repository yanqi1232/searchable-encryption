package com.bdic.model;

import java.io.Serializable;

/**
 * 文档操作请求。
 *
 * <p>下载、删除、重建索引等操作只需要传递用户可见的 docId。</p>
 */
public class DocumentRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String docId;

    public DocumentRequest(String docId) {
        this.docId = docId;
    }

    public String getDocId() {
        return docId;
    }
}
