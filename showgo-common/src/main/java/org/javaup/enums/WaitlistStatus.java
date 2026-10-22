package org.javaup.enums;

import lombok.Getter;

/**
 * 候补排队状态。
 */
public enum WaitlistStatus {

    NOT_IN_QUEUE(0, "未候补"),

    WAITING(1, "候补排队中"),

    SUCCESS(2, "已自动锁座成功"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    WaitlistStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static WaitlistStatus getRc(Integer code) {
        for (WaitlistStatus re : WaitlistStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
