package com.bdic.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 服务端统一响应对象。
 *
 * <p>success 表示操作是否成功，message 用于界面提示，data 承载具体业务结果。</p>
 */
public class ServerResponse implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean success;
    private final String message;
    private final Object data;

    public ServerResponse(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }
}
