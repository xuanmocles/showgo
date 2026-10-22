package org.javaup.delay.consumer;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.javaup.core.ConsumerTask;
import org.javaup.core.RedisKeyManage;
import org.javaup.core.SpringUtil;
import org.javaup.delay.message.DelayedSeatReleaseMessage;
import org.javaup.entity.SeatLockLog;
import org.javaup.enums.BusinessType;
import org.javaup.enums.SeatLockStatus;
import org.javaup.mapper.SeatLockLogMapper;
import org.javaup.redis.RedisCache;
import org.javaup.redis.RedisKeyBuild;
import org.javaup.service.ISeatService;
import org.javaup.service.ITicketOrderService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static org.javaup.constant.Constant.DELAY_SEAT_LOCK_RELEASE;

/**
 * 延迟队列消费者：锁座超时释放、订单支付超时关单。
 *
 * <p>锁座超时的判定依据是「锁座令牌是否还在 Redis 中」：令牌在，说明用户还没下单，
 * 需要释放；令牌不在，说明已经下单或被释放过，直接返回，天然幂等。</p>
 */
@Slf4j
@Component
public class ConsumerDelayedSeatRelease implements ConsumerTask {

    @Resource
    private RedisCache redisCache;

    @Resource
    private ISeatService seatService;

    @Resource
    private ITicketOrderService ticketOrderService;

    @Resource
    private SeatLockLogMapper seatLockLogMapper;

    @Override
    public void execute(String content) {
        try {
            DelayedSeatReleaseMessage message = JSON.parseObject(content, DelayedSeatReleaseMessage.class);
            if (message == null) {
                return;
            }
            if ("LOCK".equals(message.getType())) {
                handleLockTimeout(message);
            } else if ("ORDER".equals(message.getType())) {
                ticketOrderService.handleOrderPayTimeout(message);
            }
        } catch (Exception e) {
            log.error("延迟释放任务执行异常 content={}", content, e);
        }
    }

    private void handleLockTimeout(DelayedSeatReleaseMessage message) {
        RedisKeyBuild tokenKey = RedisKeyBuild.createRedisKey(
                RedisKeyManage.SEAT_LOCK_TOKEN_TAG_KEY, message.getSessionId(), message.getLockToken());
        String indexes = redisCache.get(tokenKey, String.class);
        if (StrUtil.isBlank(indexes)) {
            return;
        }
        List<Integer> seatIndexes = parseIndexes(indexes);
        seatService.release(message.getSessionId(), message.getUserId(), seatIndexes, null,
                BusinessType.RELEASE);
        seatLockLogMapper.update(null, new LambdaUpdateWrapper<SeatLockLog>()
                .set(SeatLockLog::getStatus, SeatLockStatus.TIMEOUT_RELEASED.getCode())
                .set(SeatLockLog::getReleaseTime, LocalDateTimeUtil.now())
                .eq(SeatLockLog::getSessionId, message.getSessionId())
                .eq(SeatLockLog::getLockToken, message.getLockToken()));
        log.info("锁座超时已释放 sessionId={} userId={} seats={}",
                message.getSessionId(), message.getUserId(), indexes);
    }

    private List<Integer> parseIndexes(String csv) {
        List<Integer> indexes = new ArrayList<>();
        for (String part : csv.split(",")) {
            if (StrUtil.isNotBlank(part)) {
                indexes.add(Integer.valueOf(part.trim()));
            }
        }
        return indexes;
    }

    @Override
    public String topic() {
        return SpringUtil.getPrefixDistinctionName() + "-" + DELAY_SEAT_LOCK_RELEASE;
    }
}
