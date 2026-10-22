package org.javaup.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.CreateTicketOrderDto;
import org.javaup.dto.PayTicketOrderDto;
import org.javaup.dto.Result;
import org.javaup.dto.TicketOrderOperateDto;
import org.javaup.dto.TicketOrderQueryDto;
import org.javaup.entity.TicketOrder;
import org.javaup.execute.RateLimitHandler;
import org.javaup.ratelimit.extension.RateLimitScene;
import org.javaup.service.ITicketOrderService;
import org.javaup.service.ITicketReconcileTaskService;
import org.javaup.service.ITicketAccessTokenService;
import org.javaup.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 票务订单接口：令牌前置授权 + 限流 + 下单/支付/取消/退票。
 */
@RestController
@RequestMapping("/ticket-order")
public class TicketOrderController {

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private ITicketAccessTokenService ticketAccessTokenService;

    @Resource
    private RateLimitHandler rateLimitHandler;

    @Resource
    private ITicketReconcileTaskService ticketReconcileTaskService;

    /** 先进场令牌：把没有资格的流量挡在选座热链路之外 */
    @GetMapping("/access-token/{sessionId}")
    public Result<String> issueAccessToken(@PathVariable("sessionId") Long sessionId) {
        Long userId = UserHolder.getUser().getId();
        rateLimitHandler.execute(sessionId, userId, RateLimitScene.ISSUE_TOKEN);
        return Result.ok(ticketAccessTokenService.issueAccessToken(sessionId, userId));
    }

    @PostMapping("/create")
    public Result<TicketOrder> create(@Valid @RequestBody CreateTicketOrderDto createTicketOrderDto,
                                      @RequestParam(value = "accessToken", required = false) String accessToken) {
        Long userId = UserHolder.getUser().getId();
        rateLimitHandler.execute(createTicketOrderDto.getSessionId(), userId, RateLimitScene.LOCK_SEAT);
        if (ticketAccessTokenService.isEnabled()) {
            if (accessToken == null
                    || !ticketAccessTokenService.validateAndConsume(
                            createTicketOrderDto.getSessionId(), userId, accessToken)) {
                return Result.fail("令牌无效或已被使用");
            }
        }
        return Result.ok(ticketOrderService.createOrder(createTicketOrderDto));
    }

    @PostMapping("/pay")
    public Result<TicketOrder> pay(@Valid @RequestBody PayTicketOrderDto payTicketOrderDto) {
        return Result.ok(ticketOrderService.pay(payTicketOrderDto));
    }

    @PostMapping("/cancel")
    public Result<TicketOrder> cancel(@Valid @RequestBody TicketOrderOperateDto operateDto) {
        return Result.ok(ticketOrderService.cancel(operateDto));
    }

    @PostMapping("/refund")
    public Result<TicketOrder> refund(@Valid @RequestBody TicketOrderOperateDto operateDto) {
        return Result.ok(ticketOrderService.refund(operateDto));
    }

    @GetMapping("/{orderId}")
    public Result<TicketOrder> detail(@PathVariable("orderId") Long orderId) {
        return Result.ok(ticketOrderService.getOrder(orderId));
    }

    @PostMapping("/my")
    public Result<List<TicketOrder>> myOrders(@Valid @RequestBody TicketOrderQueryDto queryDto) {
        return Result.ok(ticketOrderService.myOrders(queryDto));
    }

    /** 手动触发对账（生产环境由定时任务执行，这里保留手工兜底入口） */
    @PostMapping("/reconcile/{sessionId}")
    public Result<Void> reconcile(@PathVariable("sessionId") Long sessionId) {
        ticketReconcileTaskService.reconciliationTaskExecute(sessionId);
        return Result.ok();
    }
}
