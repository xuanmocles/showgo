package org.javaup.enums;

import lombok.Getter;

/**
 * 票务订单状态机：
 * 1待支付 -> 2已支付 -> 3已出票
 * 1待支付 -> 4已取消 / 6已超时
 * 2已支付 / 3已出票 -> 5已退款
 */
public enum TicketOrderStatus {

    WAIT_PAY(1, "待支付"),

    PAID(2, "已支付"),

    ISSUED(3, "已出票"),

    CANCELED(4, "已取消"),

    REFUNDED(5, "已退款"),

    TIMEOUT(6, "已超时"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    TicketOrderStatus(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static String getMsg(Integer code) {
        for (TicketOrderStatus re : TicketOrderStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static TicketOrderStatus getRc(Integer code) {
        for (TicketOrderStatus re : TicketOrderStatus.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
