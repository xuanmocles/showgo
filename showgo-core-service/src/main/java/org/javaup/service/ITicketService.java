package org.javaup.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.javaup.entity.Ticket;

import java.util.List;

public interface ITicketService extends IService<Ticket> {

    /** 我的票夹 */
    List<Ticket> myTickets(Integer status, Long sessionId);

    /** 入场核销 */
    Ticket checkIn(Long ticketId);
}
