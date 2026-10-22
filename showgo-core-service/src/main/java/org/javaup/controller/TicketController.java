package org.javaup.controller;

import jakarta.annotation.Resource;
import org.javaup.dto.Result;
import org.javaup.entity.Ticket;
import org.javaup.service.ITicketService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 票夹与入场核销。
 */
@RestController
@RequestMapping("/ticket")
public class TicketController {

    @Resource
    private ITicketService ticketService;

    @GetMapping("/my")
    public Result<List<Ticket>> myTickets(@RequestParam(value = "status", required = false) Integer status,
                                          @RequestParam(value = "sessionId", required = false) Long sessionId) {
        return Result.ok(ticketService.myTickets(status, sessionId));
    }

    @PostMapping("/check-in/{ticketId}")
    public Result<Ticket> checkIn(@PathVariable("ticketId") Long ticketId) {
        return Result.ok(ticketService.checkIn(ticketId));
    }
}
