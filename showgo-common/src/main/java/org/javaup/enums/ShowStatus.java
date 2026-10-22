package org.javaup.enums;

import lombok.Getter;

/**
 * 演出上下架状态。
 */
public enum ShowStatus {

    ONLINE(1, "上架"),

    OFFLINE(2, "下架"),

    FINISHED(3, "已结束"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    ShowStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static ShowStatus getRc(Integer code) {
        for (ShowStatus re : ShowStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
