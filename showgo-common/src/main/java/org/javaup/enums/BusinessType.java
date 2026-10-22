package org.javaup.enums;

import lombok.Getter;

/**
 * 对账流水的业务类型。
 */
public enum BusinessType {

    SUCCESS(1, "出票成功"),

    TIMEOUT(2, "支付超时"),

    FAIL(3, "出票失败"),

    CANCEL(4, "主动取消"),

    REFUND(5, "退票"),

    RELEASE(6, "锁座超时释放"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    BusinessType(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static String getMsg(Integer code) {
        for (BusinessType re : BusinessType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static BusinessType getRc(Integer code) {
        for (BusinessType re : BusinessType.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
