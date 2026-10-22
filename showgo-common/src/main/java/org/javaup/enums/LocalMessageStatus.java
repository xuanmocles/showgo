package org.javaup.enums;

import lombok.Getter;

/**
 * 本地消息表状态机：PENDING -> SENT -> ACKED，失败进入 FAILED。
 */
public enum LocalMessageStatus {

    PENDING(0, "待投递"),

    SENT(1, "已投递"),

    ACKED(2, "已消费确认"),

    FAILED(3, "投递失败"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    LocalMessageStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static LocalMessageStatus getRc(Integer code) {
        for (LocalMessageStatus re : LocalMessageStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
