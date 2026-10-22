package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.dto.SeatReconcileLogDto;
import org.javaup.entity.SeatReconcileLog;

public interface ITicketReconcileLogService extends IService<SeatReconcileLog> {

    boolean saveReconcileLog(SeatReconcileLogDto reconcileLogDto);

    /**
     * 快捷写入：座位变更的每一步都落一条流水，供对账任务比对 Redis 与 DB。
     */
    boolean saveReconcileLog(Integer logType,
                             Integer businessType,
                             String detail,
                             Long traceId,
                             Long sessionId,
                             Long userId,
                             Long orderId,
                             String seatCodes,
                             Integer beforeQty,
                             Integer changeQty,
                             Integer afterQty);
}
