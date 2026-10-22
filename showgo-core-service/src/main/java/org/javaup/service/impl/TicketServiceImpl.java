package org.javaup.service.impl;

import cn.hutool.core.date.LocalDateTimeUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.javaup.entity.Ticket;
import org.javaup.enums.BaseCode;
import org.javaup.enums.TicketStatus;
import org.javaup.exception.ShowGoFrameException;
import org.javaup.mapper.TicketMapper;
import org.javaup.service.ITicketService;
import org.javaup.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 票夹与入场核销。
 */
@Slf4j
@Service
public class TicketServiceImpl extends ServiceImpl<TicketMapper, Ticket> implements ITicketService {

    @Override
    public List<Ticket> myTickets(Integer status, Long sessionId) {
        Long userId = UserHolder.getUser().getId();
        return lambdaQuery()
                .eq(Ticket::getUserId, userId)
                .eq(Objects.nonNull(status), Ticket::getStatus, status)
                .eq(Objects.nonNull(sessionId), Ticket::getSessionId, sessionId)
                .orderByDesc(Ticket::getCreateTime)
                .list();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Ticket checkIn(Long ticketId) {
        Long userId = UserHolder.getUser().getId();
        Ticket ticket = lambdaQuery()
                .eq(Ticket::getId, ticketId)
                .eq(Ticket::getUserId, userId)
                .one();
        if (Objects.isNull(ticket)) {
            throw new ShowGoFrameException(BaseCode.TICKET_ORDER_NOT_EXIST);
        }
        if (Objects.equals(ticket.getStatus(), TicketStatus.CHECKED_IN.getCode())) {
            return ticket;
        }
        if (!Objects.equals(ticket.getStatus(), TicketStatus.VALID.getCode())) {
            throw new ShowGoFrameException("该票已退票，无法入场");
        }
        lambdaUpdate()
                .set(Ticket::getStatus, TicketStatus.CHECKED_IN.getCode())
                .set(Ticket::getCheckInTime, LocalDateTimeUtil.now())
                .set(Ticket::getUpdateTime, LocalDateTimeUtil.now())
                .eq(Ticket::getId, ticketId)
                .eq(Ticket::getUserId, userId)
                .update();
        ticket.setStatus(TicketStatus.CHECKED_IN.getCode());
        return ticket;
    }
}
