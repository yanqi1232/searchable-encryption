package com.bdic.model;

import java.io.Serializable;

/**
 * 客户端与服务端之间传输的统一消息对象。
 *
 * <p>type 表示操作类型，payload 承载对应请求或响应数据。对象通过 TLS Socket 的对象流传输。</p>
 */
public class NetworkMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum MessageType {
        // 用户认证相关操作。
        REGISTER,
        LOGIN,
        LOGOUT,

        // 文档上传和密文关键词搜索。
        UPLOAD,
        SEARCH,

        // 文档管理操作。
        LIST_DOCUMENTS,
        DOWNLOAD_DOCUMENT,
        DELETE_DOCUMENT,

        // 服务端统一响应。
        RESPONSE
    }

    private MessageType type;
    private Object payload;

    public NetworkMessage(MessageType type, Object payload) {
        this.type = type;
        this.payload = payload;
    }

    public MessageType getType() {
        return type;
    }

    public void setType(MessageType type) {
        this.type = type;
    }

    public Object getPayload() {
        return payload;
    }

    public void setPayload(Object payload) {
        this.payload = payload;
    }
}
