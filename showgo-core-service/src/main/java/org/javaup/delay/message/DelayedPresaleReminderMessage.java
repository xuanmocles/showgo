package org.javaup.delay.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 开售前提醒消息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DelayedPresaleReminderMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private LocalDateTime saleBeginTime;

    private LocalDateTime beginTime;
}
