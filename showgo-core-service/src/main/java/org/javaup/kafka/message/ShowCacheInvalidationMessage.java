package org.javaup.kafka.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 场次缓存失效广播。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShowCacheInvalidationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long sessionId;

    private String reason;
}
