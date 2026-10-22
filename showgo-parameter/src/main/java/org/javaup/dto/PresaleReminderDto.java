package org.javaup.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开售前提醒。
 */
@Data
public class PresaleReminderDto {

    private Long sessionId;

    private LocalDateTime saleBeginTime;

    private LocalDateTime beginTime;
}
