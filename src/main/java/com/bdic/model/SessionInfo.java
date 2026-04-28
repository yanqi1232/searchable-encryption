package com.bdic.model;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 登录成功后返回给客户端的会话信息。
 *
 * <p>当前客户端与服务端保持长连接，sessionId 主要用于服务端会话管理和界面调试扩展。</p>
 */
public class SessionInfo implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final String sessionId;
    private final String username;
    private final LocalDateTime expiresAt;

    public SessionInfo(String sessionId, String username, LocalDateTime expiresAt) {
        this.sessionId = sessionId;
        this.username = username;
        this.expiresAt = expiresAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUsername() {
        return username;
    }

    public LocalDateTime getExpiresAt() {
        return expiresAt;
    }
}
