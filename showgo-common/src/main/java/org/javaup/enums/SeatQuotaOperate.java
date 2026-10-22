package org.javaup.enums;

import lombok.Getter;

/**
 * 座位回滚时，是否同时回收用户在该场次的购票额度。
 */
public enum SeatQuotaOperate {

    NO(0, "保留购票额度"),

    YES(1, "回收购票额度"),
    ;

    @Getter
    private final Integer code;

    private String msg = "";

    SeatQuotaOperate(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    public String getMsg() {
        return this.msg == null ? "" : this.msg;
    }

    public static SeatQuotaOperate getRc(Integer code) {
        for (SeatQuotaOperate re : SeatQuotaOperate.values()) {
            if (re.code.intValue() == code.intValue()) {
                return re;
            }
        }
        return null;
    }
}
