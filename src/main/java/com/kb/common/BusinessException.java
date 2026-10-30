package com.kb.common;

/**
 * 业务异常，code 同时用作 HTTP 状态码。
 */
public class BusinessException extends RuntimeException {

    private final int code;

    public BusinessException(int code, String msg) {
        super(msg);
        this.code = code;
    }

    public BusinessException(String msg) {
        this(400, msg);
    }

    public int getCode() {
        return code;
    }
}
