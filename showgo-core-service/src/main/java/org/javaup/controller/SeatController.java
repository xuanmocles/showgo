package org.javaup.controller;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.javaup.dto.Result;
import org.javaup.dto.SeatLockDto;
import org.javaup.dto.SeatRecommendDto;
import org.javaup.dto.WaitlistBatchDto;
import org.javaup.dto.WaitlistDto;
import org.javaup.service.ISeatService;
import org.javaup.service.ISeatWaitlistService;
import org.javaup.stream.SeatChangeBroadcaster;
import org.javaup.vo.SeatLockVo;
import org.javaup.vo.SeatMapVo;
import org.javaup.vo.SeatRecommendVo;
import org.javaup.vo.WaitlistStatusVo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * 选座接口：座位图、连座推荐、锁座、实时变更推送、候补排队。
 */
@RestController
@RequestMapping("/seat")
public class SeatController {

    @Resource
    private ISeatService seatService;

    @Resource
    private ISeatWaitlistService seatWaitlistService;

    @Resource
    private SeatChangeBroadcaster seatChangeBroadcaster;

    @GetMapping("/map/{sessionId}")
    public Result<SeatMapVo> seatMap(@PathVariable("sessionId") Long sessionId) {
        return Result.ok(seatService.querySeatMap(sessionId));
    }

    @PostMapping("/recommend")
    public Result<SeatRecommendVo> recommend(@Valid @RequestBody SeatRecommendDto seatRecommendDto) {
        return Result.ok(seatService.recommend(seatRecommendDto));
    }

    @PostMapping("/lock")
    public Result<SeatLockVo> lock(@Valid @RequestBody SeatLockDto seatLockDto) {
        return Result.ok(seatService.lock(seatLockDto));
    }

    /** 实时座位图：订阅后其它用户锁座/购票/退票会即时推送到前端 */
    @GetMapping(value = "/stream/{sessionId}", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@PathVariable("sessionId") Long sessionId) {
        return seatChangeBroadcaster.subscribe(sessionId);
    }

    @PostMapping("/waitlist/join")
    public Result<WaitlistStatusVo> joinWaitlist(@Valid @RequestBody WaitlistDto waitlistDto) {
        return Result.ok(seatWaitlistService.joinWaitlist(waitlistDto));
    }

    @PostMapping("/waitlist/cancel")
    public Result<WaitlistStatusVo> cancelWaitlist(@RequestBody WaitlistDto waitlistDto) {
        return Result.ok(seatWaitlistService.cancelWaitlist(waitlistDto.getSessionId()));
    }

    @GetMapping("/waitlist/status/{sessionId}")
    public Result<WaitlistStatusVo> waitlistStatus(@PathVariable("sessionId") Long sessionId) {
        return Result.ok(seatWaitlistService.getStatus(sessionId));
    }

    @PostMapping("/waitlist/status/batch")
    public Result<List<WaitlistStatusVo>> waitlistStatusBatch(@Valid @RequestBody WaitlistBatchDto batchDto) {
        return Result.ok(seatWaitlistService.getStatusBatch(batchDto.getSessionIds()));
    }
}
