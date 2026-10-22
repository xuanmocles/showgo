package org.javaup.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.javaup.dto.SeatReconcileLogDto;
import org.javaup.entity.SeatReconcileLog;
import org.javaup.enums.ReconciliationStatus;
import org.javaup.mapper.SeatReconcileLogMapper;
import org.javaup.service.ITicketReconcileLogService;
import org.javaup.toolkit.SnowflakeIdGenerator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 座位变更对账流水。
 */
@Slf4j
@Service
public class TicketReconcileLogServiceImpl
        extends ServiceImpl<SeatReconcileLogMapper, SeatReconcileLog>
        implements ITicketReconcileLogService {

    @Resource
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Override
    public boolean saveReconcileLog(SeatReconcileLogDto reconcileLogDto) {
        SeatReconcileLog entity = new SeatReconcileLog()
                .setId(snowflakeIdGenerator.nextId())
                .setOrderId(reconcileLogDto.getOrderId())
                .setSessionId(reconcileLogDto.getSessionId())
                .setUserId(reconcileLogDto.getUserId())
                .setSeatCodes(reconcileLogDto.getSeatCodes())
                .setTraceId(reconcileLogDto.getTraceId())
                .setLogType(reconcileLogDto.getLogType())
                .setBusinessType(reconcileLogDto.getBusinessType())
                .setBeforeQty(reconcileLogDto.getBeforeQty())
                .setChangeQty(reconcileLogDto.getChangeQty())
                .setAfterQty(reconcileLogDto.getAfterQty())
                .setDetail(reconcileLogDto.getDetail())
                .setReconciliationStatus(ReconciliationStatus.PENDING.getCode())
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        return save(entity);
    }

    @Override
    public boolean saveReconcileLog(Integer logType,
                                    Integer businessType,
                                    String detail,
                                    Long traceId,
                                    Long sessionId,
                                    Long userId,
                                    Long orderId,
                                    String seatCodes,
                                    Integer beforeQty,
                                    Integer changeQty,
                                    Integer afterQty) {
        SeatReconcileLogDto dto = new SeatReconcileLogDto();
        dto.setOrderId(orderId);
        dto.setSessionId(sessionId);
        dto.setUserId(userId);
        dto.setSeatCodes(seatCodes);
        dto.setTraceId(traceId);
        dto.setLogType(logType);
        dto.setBusinessType(businessType);
        dto.setBeforeQty(beforeQty);
        dto.setChangeQty(changeQty);
        dto.setAfterQty(afterQty);
        dto.setDetail(detail);
        return saveReconcileLog(dto);
    }
}
