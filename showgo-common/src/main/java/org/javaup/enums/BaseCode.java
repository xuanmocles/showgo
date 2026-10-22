package org.javaup.enums;

import lombok.Getter;

/**
 * 接口返回码。
 */
public enum BaseCode {

    SUCCESS(0, "OK"),

    /* ---------------- 演出与场次 10001-10099 ---------------- */
    SHOW_NOT_EXIST(10001, "演出不存在"),
    SESSION_NOT_EXIST(10002, "场次不存在"),
    SESSION_NOT_ON_SALE(10003, "场次尚未开售"),
    SESSION_SALE_ENDED(10004, "场次已停止售票"),
    SESSION_SOLD_OUT(10005, "场次已售罄"),
    SESSION_CANCELED(10006, "场次已取消"),
    SESSION_ALREADY_BEGUN(10007, "场次已开演"),
    SESSION_PAUSED(10008, "场次已停止售票"),

    /* ---------------- 座位 10101-10199 ---------------- */
    SEAT_MAP_NOT_READY(10101, "座位图尚未初始化"),
    SEAT_NOT_EXIST(10102, "座位不存在"),
    SEAT_NOT_AVAILABLE(10103, "座位已被选走，请重新选择"),
    SEAT_NOT_CONTINUOUS(10104, "请选择同一分区同一排的连续座位"),
    SEAT_LOCK_EXPIRED(10105, "锁座已过期，请重新选座"),
    SEAT_LOCK_TOKEN_INVALID(10106, "锁座凭证无效"),
    SEAT_LIMIT_EXCEEDED(10107, "超出单场次限购数量"),
    SEAT_AREA_MISMATCH(10108, "选择的座位不在同一票档"),
    NO_CONTINUOUS_SEAT(10109, "该区域没有满足数量的连续空位"),

    /* ---------------- 限流 10201-10299 ---------------- */
    TICKET_RATE_LIMIT_IP_EXCEEDED(10201, "请求过于频繁，请稍后再试"),
    TICKET_RATE_LIMIT_USER_EXCEEDED(10202, "操作过于频繁，请稍后再试"),

    /* ---------------- 订单 10301-10399 ---------------- */
    TICKET_ORDER_NOT_EXIST(10301, "订单不存在"),
    TICKET_ORDER_ALREADY_PAID(10302, "订单已支付"),
    TICKET_ORDER_CANCELED(10303, "订单已取消"),
    TICKET_ORDER_STATUS_ILLEGAL(10304, "当前订单状态不支持该操作"),
    TICKET_ORDER_EXPIRED(10305, "订单支付超时已关闭"),
    TICKET_ORDER_REFUNDED(10306, "订单已退票"),
    TICKET_ORDER_EXIST(10307, "该场次已存在有效订单"),
    TICKET_ORDER_AMOUNT_MISMATCH(10308, "订单金额校验失败"),

    /* ---------------- 用户 20000-20099 ---------------- */
    USER_NOT_EXIST(20000, "用户不存在"),
    USER_LEVEL_NOT_ENOUGH(20001, "会员等级不足，暂不能参与优先购票"),
    USER_NOT_LOGIN(20002, "请先登录"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    BaseCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static String getMsg(Integer code) {
        for (BaseCode re : BaseCode.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re.msg;
            }
        }
        return "";
    }

    public static BaseCode getRc(Integer code) {
        for (BaseCode re : BaseCode.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
