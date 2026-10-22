package org.javaup.enums;

import lombok.Getter;

/**
 * 场次状态。
 */
public enum SessionStatus {

    WAIT_SALE(1, "待开售"),

    ON_SALE(2, "售票中"),

    SOLD_OUT(3, "已售罄"),

    FINISHED(4, "已结束"),

    CANCELED(5, "已取消"),

    PAUSED(6, "已停售"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    SessionStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static SessionStatus getRc(Integer code) {
        for (SessionStatus re : SessionStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
