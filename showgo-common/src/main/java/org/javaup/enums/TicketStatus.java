package org.javaup.enums;

import lombok.Getter;

/**
 * 单张票的状态。
 */
public enum TicketStatus {

    VALID(1, "有效"),

    REFUNDED(2, "已退票"),

    CHECKED_IN(3, "已入场"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    TicketStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static TicketStatus getRc(Integer code) {
        for (TicketStatus re : TicketStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
