package com.bdic.model;

import java.io.Serializable;

/**
 * 登录/注册请求载荷。
 *
 * <p>该对象通过 TLS 通道传输，服务端收到后只保存密码摘要，不落库明文密码。</p>
 */
public class LoginRequest implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String username;
    private final String password;

    public LoginRequest(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
