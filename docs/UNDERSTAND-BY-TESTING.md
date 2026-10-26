# 用测试吃透 ShowGo：一份"追踪式"学习手册

> 目标：不是验证功能对不对，而是**在脑子里建立这套系统的运行时模型**。
> 学完的标准：随便给你一个时刻，你能说出 Redis 里有哪些 key、DB 里有哪些行、
> 消息走到了哪一步、下一步可能出什么错。

---

## 一、核心方法：追状态，不点按钮

普通测法：点"锁座"→ 看到提示成功 → 结束。

追踪式测法：

```
点"锁座"
  → 立刻打开 Redis 看：座位图那一位变了吗？lock Hash 多了什么？token key 是什么？用户额度变了吗？
  → 打开 MySQL 看：哪张表多了一行？哪个字段是这次操作写的？
  → 打开日志看：这一步打了什么日志？有没有对应的 Kafka 消息？
  → 问自己：如果这一步失败，前面已经改动的数据谁来回收？
```

**每做一步，就回答一次"改了什么、为什么改这里、失败怎么办"。** 这三个问题答得出来，才是真做过。

> 配套的**逐段代码讲解**见本文最后「十一、关键代码逐段讲解」，建议先跑实验、再对照代码看。

---

## 二、先记住这张"状态地图"

面试时你能画出这张图，基本就赢了。

| 层 | 位置 | 存什么 |
| --- | --- | --- |
| Redis | `showgo-ticket:seat:map:{1001}` | BitField 座位图，每座 2bit（0可售/1锁定/2已售/3不可用） |
| Redis | `showgo-ticket:seat:lock:{1001}` | Hash：座位索引 → lockToken |
| Redis | `showgo-ticket:seat:token:{1001}:{token}` | 锁座凭证，值是**座位索引列表**（如 `42,43`） |
| Redis | `showgo-ticket:seat:user:{1001}` | Hash：用户 → 该场次已锁+已购张数（限购用） |
| Redis | `showgo-ticket:seat:trace:{1001}` | Hash：traceId → 扣减/恢复流水（对账用） |
| Redis | `showgo-ticket:session:stock:{1001}` | 场次余票计数 |
| MySQL | `tb_show_seat_X` | 座位明细（锁座时不写，出票消费时才写） |
| MySQL | `tb_ticket_order_X` | 订单状态机 |
| MySQL | `tb_ticket_X` | 票（支付后生成） |
| MySQL | `tb_local_message_X` | 本地消息表（Outbox） |
| MySQL | `tb_seat_reconcile_log_X` | 对账流水 |
| MySQL | `tb_seat_lock_log` | 锁座流水（广播表） |
| Kafka | `showgo-ticket_order_topic` | 出票消息 |
| Kafka | `showgo-seat_change_topic` | 座位变更广播（SSE 用） |

> 分片提示：场次 1001 / 用户 10001 的订单在 `showgo_1.tb_ticket_order_0`；
> 而按 order_id 分片的票、消息、对账流水会落在 `showgo_0.tb_*_0`（实测过）。
> 搞不清时用 `union all` 横扫 4 个物理表。

---

## 三、实验 1：追踪一次完整下单（最重要，约 1 小时）

固定用：**场次 1001，座位索引 42（座位号 A-03-03），用户 13800000001**

### 准备：拿一个 token

```powershell
curl.exe -s -X POST "http://localhost:8080/api/user/code?phone=13800000001"
docker compose logs backend --tail 50 | Select-String "code="
# 把日志里的 6 位验证码填进去
$token = (Invoke-RestMethod -Uri "http://localhost:8080/api/user/login" -Method Post `
  -ContentType "application/json" -Body '{"phone":"13800000001","code":"123456"}').data
Write-Output $token
```

### 步骤 0：初始状态（先建立基线）

```powershell
# 座位 42 的状态（offset = 42 * 2 = 84）
docker exec showgo-redis redis-cli BITFIELD "showgo-ticket:seat:map:{1001}" GET u2 84
# 场次余票
docker exec showgo-redis redis-cli GET "showgo-ticket:session:stock:{1001}"
# DB 座位明细
docker exec showgo-mysql mysql -uroot -proot -N -e "select seat_code,status,order_id from showgo_1.tb_show_seat_0 where session_id=1001 and seat_code='A-03-03';"
```

**应该看到**：`0`、`688`、`A-03-03 0 NULL`

**要理解**：座位「可售」的三个副本——Redis 位图、Redis 余票计数、DB 座位明细，此刻是一致的。

### 步骤 1：打开座位图（观察缓存与预热）

浏览器打开该场次，或：

```powershell
curl.exe -s "http://localhost:8080/api/seat/map/1001" | Select-Object -First 1
docker exec showgo-redis redis-cli --scan --pattern "showgo-ticket:seat:*"
docker exec showgo-redis redis-cli TTL "showgo-ticket:seat:map:{1001}"
```

**看什么**：座位图 key、`index:code` 映射、`session:stock` 是否都存在；TTL 是不是到散场后一天。

**要理解**：读座位图走的是「本地缓存 → Redis → 布隆过滤器 → 空值缓存 → 锁+DB」；座位图本身**不进本地缓存**，因为它要求强一致。

### 步骤 2：锁座（观察"只写 Redis，不写 DB"）

```powershell
$h = @{ Authorization = $token }
$lock = Invoke-RestMethod -Uri "http://localhost:8080/api/seat/lock" -Method Post -Headers $h `
  -ContentType "application/json" -Body '{"sessionId":1001,"seatIndexes":[42]}'
$lock.data
$lockToken = $lock.data.lockToken

# ① 位图那一位
docker exec showgo-redis redis-cli BITFIELD "showgo-ticket:seat:map:{1001}" GET u2 84
# ② 锁座归属
docker exec showgo-redis redis-cli HGET "showgo-ticket:seat:lock:{1001}" 42
# ③ 锁座凭证（值 = 座位索引列表）
docker exec showgo-redis redis-cli GET "showgo-ticket:seat:token:{1001}:$lockToken"
# ④ 用户额度
docker exec showgo-redis redis-cli HGET "showgo-ticket:seat:user:{1001}" 10001
# ⑤ DB 座位明细（应该还没变）
docker exec showgo-mysql mysql -uroot -proot -N -e "select status,order_id from showgo_1.tb_show_seat_0 where session_id=1001 and seat_code='A-03-03';"
# ⑥ 锁座流水
docker exec showgo-mysql mysql -uroot -proot -N -e "select status,seat_codes,trace_id from showgo_1.tb_seat_lock_log where session_id=1001 order by create_time desc limit 1;"
```

**应该看到**：位图 `1`、Hash 里是 token、凭证是 `42`、额度 `1`、**DB 仍是 0**、锁座流水 status=1 且 trace_id 非空。

**要回答自己**：
- 为什么锁座不写 DB？（性能：锁座是最热的动作，DB 承受不了）
- 如果不写 DB，服务重启后锁还在吗？（在 Redis，凭证有 TTL；丢了由座位图 TTL / 对账兜底）
- 锁座流水为什么要记 trace_id？（排查与对账时能定位到具体一次操作）

### 步骤 3：下单（观察 Redis 成交 + DB 事务 + Outbox）

```powershell
$acc = Invoke-RestMethod -Uri "http://localhost:8080/api/ticket-order/access-token/1001" -Headers $h
$order = Invoke-RestMethod -Uri "http://localhost:8080/api/ticket-order/create?accessToken=$($acc.data)" `
  -Method Post -Headers $h -ContentType "application/json" `
  -Body "{`"sessionId`":1001,`"lockToken`":`"$lockToken`"}"
$order.data

# ① 位图：应变成 2（已售）
docker exec showgo-redis redis-cli BITFIELD "showgo-ticket:seat:map:{1001}" GET u2 84
# ② 锁座归属：应被删除（返回空）
docker exec showgo-redis redis-cli HGET "showgo-ticket:seat:lock:{1001}" 42
# ③ 凭证：应已被删除
docker exec showgo-redis redis-cli EXISTS "showgo-ticket:seat:token:{1001}:$lockToken"
# ④ 余票：688 -> 687
docker exec showgo-redis redis-cli GET "showgo-ticket:session:stock:{1001}"
# ⑤ 扣减流水
docker exec showgo-redis redis-cli HGETALL "showgo-ticket:seat:trace:{1001}"
```

紧接着（2 秒内投递器会发 Kafka，消费端落库）：

```powershell
# ⑥ DB 座位明细：status=2，order_id 有值
docker exec showgo-mysql mysql -uroot -proot -N -e "select status,order_id from showgo_1.tb_show_seat_0 where session_id=1001 and seat_code='A-03-03';"
# ⑦ 订单（status=1 待支付）
docker exec showgo-mysql mysql -uroot -proot -N -e "select order_no,status,ticket_count,amount,seat_codes from showgo_1.tb_ticket_order_0 where id=$($order.data.id);"
# ⑧ 本地消息（0待投递 / 1已投递 / 2已确认）
docker exec showgo-mysql mysql -uroot -proot -N -e "select biz_id,status,retry_count from showgo_0.tb_local_message_0 where biz_id=$($order.data.id);"
# ⑨ 对账流水（log_type=-1 扣减）
docker exec showgo-mysql mysql -uroot -proot -N -e "select order_id,log_type,business_type,detail from showgo_0.tb_seat_reconcile_log_0 where order_id=$($order.data.id);"
```

**要回答自己**：
- 这一步为什么不是"先写 DB 再扣 Redis"？（顺序反了会超卖）
- 如果 DB 事务失败，Redis 里已经变成 2 的座位谁来还？（createOrder 的 catch 里补偿回滚）
- 本地消息表什么时候写的？和订单是不是同一个事务？（是，所以能保证"有单必有消息"）
- 为什么 `tb_show_seat.status` 要等 Kafka 消费才变，而不是下单时直接写？（把非核心路径异步化，缩短下单耗时）

### 步骤 4：支付出票

```powershell
$paid = Invoke-RestMethod -Uri "http://localhost:8080/api/ticket-order/pay" -Method Post -Headers $h `
  -ContentType "application/json" -Body "{`"orderId`":$($order.data.id),`"payType`":1}"
$paid.data.status      # 3 = 已出票

# 票（两行？这次只买了 1 张，所以 1 行）
docker exec showgo-mysql mysql -uroot -proot -N -e "select seat_code,price,status from showgo_0.tb_ticket_0 where order_id=$($order.data.id);"
```

**要理解**：票是在支付时才生成的，一票一座；订单状态 1→3。

### 步骤 5：退票回流

```powershell
$refund = Invoke-RestMethod -Uri "http://localhost:8080/api/ticket-order/refund" -Method Post -Headers $h `
  -ContentType "application/json" -Body "{`"orderId`":$($order.data.id),`"reason`":`"实验`"}"
$refund.data.status    # 5 = 已退款

# 座位是否回到可售
docker exec showgo-redis redis-cli BITFIELD "showgo-ticket:seat:map:{1001}" GET u2 84   # 0
docker exec showgo-redis redis-cli GET "showgo-ticket:session:stock:{1001}"              # 688
docker exec showgo-mysql mysql -uroot -proot -N -e "select status,order_id from showgo_1.tb_show_seat_0 where session_id=1001 and seat_code='A-03-03';"
# 恢复流水（log_type=1）
docker exec showgo-redis redis-cli HGETALL "showgo-ticket:seat:trace:{1001}"
```

**要理解**：退票不是"把状态改回去"这么简单——座位、余票、用户额度、票状态、对账流水、候补队列都要一起处理。

---

## 四、实验 1 的"状态演变表"（建议自己手抄一遍）

| 时刻 | 位图 | lock Hash | token key | 用户额度 | 流水 | 余票 | DB 座位 | 订单 | 本地消息 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 初始 | 0 | 无 | 无 | 0 | 无 | 688 | 0 | — | — |
| 锁座后 | **1** | **42→token** | **42** | **1** | 无 | 688 | 0 | — | — |
| 下单后（Redis） | **2** | 删除 | 删除 | 1 | **-1** | **687** | 0 | **1 待支付** | **0 待投递** |
| 消费完成后 | 2 | — | — | 1 | -1 | 687 | **2 + orderId** | 1 | **2 已确认** |
| 支付后 | 2 | — | — | 1 | -1 | 687 | 2 | **3 已出票** | 2 |
| 退票后 | **0** | — | — | **0** | **+1 恢复** | **688** | **0** | **5 已退款** | 2 |

> 这张表能默画出来，说明你真的理解了这套系统。

---

## 五、实验 2：超时链路（理解"座位为什么不会永远被占"）

把超时临时调短（不用改代码、不用重建镜像）：

```powershell
$env:SEAT_LOCK_SECONDS = "30"
$env:PAY_TIMEOUT_SECONDS = "60"
docker compose up -d backend
```

**A. 锁座超时**
1. 锁一个座位，不下单；
2. 等 30 秒；
3. 看位图是否回到 0、日志是否出现 `锁座超时已释放`；
4. 看 `seat_lock_log` 的状态是否变成 4（超时释放）。

**B. 支付超时**
1. 锁座 → 下单（不支付）；
2. 等 60 秒；
3. 订单应变成 6（已超时），座位回流；
4. 再等 5 分钟跑对账，确认对账任务不会误判。

**要回答自己**：如果延迟消息没投出去，座位会不会永久卡住？→ 不会，对账任务会扫 `expire_time < now` 的待支付订单兜底关单。

---

## 六、实验 3：退票 + 候补补位（理解"座位回流给谁"）

1. 用用户 A 把所有剩余票买光（或直接改 Redis 把座位都置 2——不建议，改 DB 更安全）；
2. 用用户 B 点"售罄登记候补"，看 Redis：
   ```powershell
   docker exec showgo-redis redis-cli ZRANGE "showgo-ticket:waitlist:zset:{1001}" 0 -1 WITHSCORES
   ```
3. 用户 A 退一张票；
4. 看候补 ZSet 是否减少、座位是否被用户 B 锁定、日志是否出现候补通知。

**要理解**：候补的 score 是加入时间戳，天然先到先得；补位后同样受锁座 TTL 约束。

---

## 七、实验 4：反向破坏（理解每个机制"为什么存在"）

每个实验都问自己一句：**如果没有这个机制，会发生什么？**

| 破坏方式 | 操作 | 你会看到 | 说明什么 |
| --- | --- | --- | --- |
| Kafka 停掉 | `docker compose stop kafka` 后下单 | 下单仍成功，`local_message.status=0`；恢复后变 2 | 本地消息表的意义 |
| 删座位图 key | `redis-cli DEL "showgo-ticket:seat:map:{1001}"` | 下次读座位图时重建（以 DB 为准） | DB 才是事实来源 |
| 删对账流水 | `HDEL trace:{1001} <traceId>` | 对账触发后从 DB 回填 | 对账的容错能力 |
| 删锁座凭证 | `DEL token:{1001}:<token>` | 延迟释放不再生效（当前限制） | 为什么需要"LOCKED 但无凭证"的扫描 |
| 把限购改成 1 | 在 compose 的 backend.environment 里临时加 `TICKET_SEAT_MAX_SEAT_PER_USER: 1` 后重启后端 | 第二张被拒 | 参数的实际作用 |
| 停后端 | `docker compose stop backend` | 前端 `/api` 返回 502 | Nginx 代理确实在工作 |

> 做完记得恢复：`docker compose start kafka`、`docker compose up -d backend`、把环境变量去掉。

---

## 八、实验 5：动手改一处（"真的做过"的最强证明）

面试官判断你是不是真的做过，最简单的方法是问："你改过什么？为什么改？"

选一个 1~2 小时能完成的小改造（先 `git checkout -b lab/xxx`，改完可以不留）：

**选项 A：把座位图从 2bit 改成 1bit**
只能表达"可售/已售"，无法区分"锁定中"。改完你会发现：
- 锁座和下单无法区分，延迟释放也无从判断；
- 于是你能亲口解释"为什么必须是 2bit"。

**选项 B：给出票消息加 orderId 作为 Kafka key**
让同一订单的消息落到同一分区。改完你能讲清：
- Kafka 分区与顺序的关系；
- 为什么现在"每单只有一条消息"所以顺序问题不明显，但仍是应该补的点。

**选项 C：给订单加一个"来源渠道"字段**
从 entity → mapper → DTO → controller → 前端透传全链路改一遍。
改完你对分层和数据流会有肌肉记忆，面试时讲"一次需求怎么落地"最真实。

---

## 九、自检：能不看代码回答这 12 个问题吗？

1. 用户点一下座位，Redis 里发生了哪 4 件事？
2. 锁座为什么不写数据库？那服务重启后锁还在不在？
3. 下单时为什么还要再校验一次座位归属？不校验会怎样？
4. 订单、路由、本地消息、对账流水为什么能在一个事务里？
5. 本地消息表的状态有哪几个？谁把它从 0 推到 2？
6. 出票消息的消费端如果重复消费，会发生什么？
7. 座位图 Redis key 如果被删了，系统怎么恢复？
8. 对账任务处理哪三类差异？30 秒容忍窗口是干嘛的？
9. 锁座超时和支付超时，判定依据分别是什么？
10. 为什么退票后座位能立刻卖给别人，而不会重复卖？
11. 按订单号查订单，分片是怎么路由的？
12. 这套系统现在的瓶颈在哪？你会先优化哪里？

> 全部能答，且能说出对应的 Redis key / 表名 / 代码位置，就说明你真的吃透了。

---

## 十、三天安排

| 天 | 内容 | 产出 |
| --- | --- | --- |
| 第 1 天 | 实验 1（追踪一次完整下单）+ 手抄状态演变表 | 能在白板上画出完整链路 |
| 第 2 天 | 实验 2/3/4（超时、候补、故障注入） | 能讲清每个机制的失败分支 |
| 第 3 天 | 实验 5（动手改一处）+ 自检 12 问 + 录音复述 | 有自己的改造故事 |

**最后一步很重要**：对着手机录音，用 5 分钟讲一遍"用户从选座到退票，系统里发生了什么"。
录完回听——卡壳的地方，就是还没真正理解的地方。

---

## 十一、关键代码逐段讲解

> 这一节和前面的实验一一对应：你在 Redis / MySQL 里看到的状态变化，都能在这里找到"是谁写的"。
> 每段按 **这段在干什么 → 为什么这么写 → 去掉会怎样** 来讲。

### 11.1 代码地图

| 关注点 | 文件 | 关键方法 |
| --- | --- | --- |
| 座位图怎么编码 | [seatMapRead.lua](../showgo-core-service/src/main/resources/lua/seatMapRead.lua) | 位解码循环 |
| 锁座（核心） | [seatLock.lua](../showgo-core-service/src/main/resources/lua/seatLock.lua) | 整个脚本 |
| 锁座的 Java 编排 | [SeatServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/SeatServiceImpl.java) | `lock()` |
| 下单成交（核心） | [seatConfirm.lua](../showgo-core-service/src/main/resources/lua/seatConfirm.lua) | 整个脚本 |
| 下单的 Java 编排 | [TicketOrderServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/TicketOrderServiceImpl.java) | `createOrder()` / `createOrderTx()` |
| 可靠投递（Outbox） | [LocalMessageServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/LocalMessageServiceImpl.java) | `dispatchPending()` |
| 消费与失败补偿 | [TicketOrderConsumer.java](../showgo-core-service/src/main/java/org/javaup/kafka/consumer/TicketOrderConsumer.java) | `beforeConsume()` / `afterConsumeFailure()` |
| 超时释放 | [ConsumerDelayedSeatRelease.java](../showgo-core-service/src/main/java/org/javaup/delay/consumer/ConsumerDelayedSeatRelease.java) | `handleLockTimeout()` |
| 对账 | [TicketReconcileTaskServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/TicketReconcileTaskServiceImpl.java) | `reconcileRedisDeductWithoutOrder()` 等 |
| 缓存读链路 | [ShowSessionServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/ShowSessionServiceImpl.java) | `queryDetail()` |
| 管理端发布场次 | [AdminServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/AdminServiceImpl.java) | `createSession()` |
| 鉴权 | [MvcConfig.java](../showgo-core-service/src/main/java/org/javaup/config/MvcConfig.java) | `addInterceptors()` |

### 11.2 先看"一次锁座"写了几处状态

读代码前先记住这张表，后面每一段都能对号入座：

| 状态 | 存在哪 | 由谁写 | 什么时候 |
| --- | --- | --- | --- |
| 座位四态 | Redis BitField | `seatLock.lua` / `seatConfirm.lua` / `seatRollback.lua` | 锁座 / 下单 / 回滚 |
| 座位归属 | Redis Hash `seat:lock` | `seatLock.lua` 写、`seatConfirm.lua` 删 | 锁座 / 下单 |
| 锁座凭证 | Redis String `seat:token` | `seatLock.lua` 写（值=索引列表）、`seatConfirm.lua` 删 | 锁座 / 下单 |
| 用户额度 | Redis Hash `seat:user` | `seatLock.lua` 加、`seatRollback.lua` 减 | 锁座 / 释放 |
| 扣减流水 | Redis Hash `seat:trace` | `seatConfirm.lua` / `seatRollback.lua` | 成交 / 回滚 |
| 场次余票 | Redis String | `seatConfirm.lua` 减、`seatRollback.lua` 加 | 成交 / 回滚 |
| 锁座流水 | MySQL `tb_seat_lock_log` | `SeatServiceImpl.lock()` | 锁座后 |
| 订单/路由/Outbox/对账 | MySQL 4 张表 | `createOrderTx()` | 下单事务 |
| 座位明细 | MySQL `tb_show_seat` | Kafka 消费者 | 出票消息消费后 |

### 11.3 座位图怎么编码（BitField）

**编码规则**：每个座位 2 bit，`00=可售`、`01=锁定`、`10=已售`、`11=不可用`。
座位索引 `i` 对应位偏移 `i * 2`。

读取时，[seatMapRead.lua](../showgo-core-service/src/main/resources/lua/seatMapRead.lua) 一次性 GET 出整条位图，再在 Lua 内解码：

```lua
local raw = redis.call('get', KEYS[1])
for index = 0, total - 1 do
    local bitOffset = index * 2
    local byte = string.byte(raw, math.floor(bitOffset / 8) + 1) or 0
    local shift = 6 - (bitOffset % 8)
    out[#out + 1] = tostring(math.floor(byte / (2 ^ shift)) % 4)
end
```

**逐行理解**

- `bitOffset = index * 2`：第 42 个座位 → 第 84 个 bit。
- `floor(84 / 8) = 10`：位于第 11 个字节（Lua 的 `string.byte` 从 1 开始计数）。
- `84 % 8 = 4`：在这个字节里是第 4 个 bit。
- `shift = 6 - 4 = 2`：Redis 的位序是**高位在前**，所以取 `(byte >> 2) & 3`。
- 末尾 `% 4`：只保留低 2 位，得到 0~3 的状态码。

**为什么不用 Hash / Set**

- 位图是**稠密矩阵**，内存按座位数线性增长且极省：`座位数 × 2bit ÷ 8 = 座位数/4` 字节；
- Hash 每个 field 有独立开销，5 万座会到 MB 级；
- 位图能用一条 `BITFIELD` 一次改多个相邻座位，正好匹配"连座"语义。

**去掉会怎样**：如果改成每个座位 `GET` 一次，688 个座位就是 688 次网络往返，中间还会被其他请求插入。

### 11.4 `seatLock.lua`：一次调用做完四件事

文件：[seatLock.lua](../showgo-core-service/src/main/resources/lua/seatLock.lua)（约 20-54 行）

```lua
-- ① 一次 GET 出整条位图，在 Lua 内解码
local raw = redis.call('get', seatMapKey)
if not raw then return 10101 end

-- ② 逐个校验：可售 -> 连续
for i = 6, 5 + seatCount do
    local index = tonumber(ARGV[i])
    local status = statusAt(index)
    if status ~= 0 then return 10103 end
    if prevIndex ~= nil and index ~= prevIndex + 1 then return 10104 end
    prevIndex = index
end

-- ③ 限购：该场次"已锁 + 已购"总数不能超上限
local current = tonumber(redis.call('hget', userKey, userId) or '0')
if maxPerUser > 0 and current + seatCount > maxPerUser then return 10107 end
```

**为什么校验必须放在写之前**：Lua 的原子性只保证"执行期间不被打断"，**不保证出错回滚**。
所以脚本结构必须是"先把所有可能失败的判断做完，再统一写"。

**写入部分**（约 56-81 行）：

```lua
-- ④ 批量置为锁定态
local writeArgs = {'bitfield', seatMapKey}
-- ... 每个座位追加 set u2 <offset> 1
redis.call(unpack(writeArgs))

-- ⑤ 记录归属 + 凭证 + 用户额度
redis.call('hset', lockHashKey, index, lockToken)
redis.call('set', lockTokenKey, table.concat(indexes, ','), 'EX', ttlSeconds)
redis.call('hincrby', userKey, userId, seatCount)
```

**三个容易忽略的点**

1. 位图和锁座归属必须写在**同一次** `EVAL` 里，否则会出现"位图锁了、归属没记"；
2. 凭证的值存的是**座位索引列表**（`42,43`），不是 token —— 下一段会讲这个坑；
3. 用户额度的 TTL 要覆盖锁座有效期，否则锁还没过期、额度先失效，限购就被绕过。

**面试怎么讲**

> "锁座要做四件事：校验状态、校验连续、校验限购、原子占位。用 Java 做是 4 次往返且有竞态窗口；
> 用 Lua 一次做完，并且把校验全部前置，因为 Lua 没有回滚语义。"

### 11.5 `seatConfirm.lua`：为什么一定要二次校验归属

文件：[seatConfirm.lua](../showgo-core-service/src/main/resources/lua/seatConfirm.lua)（约 17-47 行）

```lua
-- 令牌 Key 不存在 = 已过期或已被消费
if not redis.call('get', lockTokenKey) then
    return string.format('{"code": %d}', 10105)
end
local lockToken = ARGV[7 + seatCount]

-- 二次校验：这些座位必须仍属于本次 lockToken
for i = 7, 6 + seatCount do
    local index = tonumber(ARGV[i])
    local owner = redis.call('hget', lockHashKey, index)
    if owner ~= lockToken then
        return string.format('{"code": %d}', 10106)
    end
end
```

**这里有一个真实踩过的坑（很适合作为面试里的"难题"）**

最初脚本写的是 `local lockToken = redis.call('get', lockTokenKey)`，用这个值去和 `hget` 的归属比对。
但凭证 Key 的**值**存的是座位索引（`42,43`），真正的 token 只在 Key 的名字里，所以归属校验永远不通过，
下单 100% 报"锁座凭证无效"。

定位过程：日志拿到错误码 10106 → 直接读 Redis 看到 Hash 里的 token 是对的 → 用只读脚本打印比较结果 →
发现 `tokenValue=42,43`，才意识到取错了对象。修复就是**把 lockToken 作为参数传进来**。

**紧接着的三步**

```lua
-- 座位：1(锁定) -> 2(已售)
table.insert(writeArgs, 'set') ... 2
-- 清掉归属与凭证
redis.call('hdel', lockHashKey, index)
redis.call('del', lockTokenKey)   -- 脚本末尾
-- 扣场次余票 + 写扣减流水
local afterQty = beforeQty - seatCount
redis.call('hset', traceKey, traceId, cjson.encode({ ... }))
```

**为什么返回 before/after 数量**：Java 侧要拿它写对账流水，而且**补偿回滚时要把 before/after 反过来传**。

### 11.6 `SeatServiceImpl.lock()`：脚本之外的编排与补偿

文件：[SeatServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/SeatServiceImpl.java)（`lock()`，约 192 行）

Java 在调 Lua 之前做了三件"业务规则"校验：

```java
validateSaleWindow(detail);                       // 未开售 / 已停售 / 已结束
if (indexes.size() > maxSeatPerOrder) { ... }     // 单笔上限
SeatArea area = resolveArea(detail, indexes);     // 同一分区 + 同一排 + 连续
```

`resolveArea` 会算出"索引落在哪个分区、哪一排"，并检查 `index` 是否严格递增 1。
这样 Lua 里只需要做"状态"和"连续性"的原子校验，业务语义留在 Java。

**最关键的是锁座成功之后的补偿**：

```java
try {
    seatLockLogMapper.insert(new SeatLockLog()...);  // 锁座流水
    broadcast(...LOCK...);                            // 通知前端
    scheduleLockRelease(...);                         // 登记 15 分钟延迟释放
} catch (Exception e) {
    rollbackLockedSeats(...);   // 任何一步失败，把刚锁的座位还回去
    throw e;
}
```

**为什么需要这段**：Redis 已经锁了座位，如果后面落库失败却没有补偿，座位就会变成"孤儿锁"——
别人选不了、也没有任何订单，直到座位图 TTL 到期才恢复。测试里真的遇到过：
`tb_seat_lock_log.trace_id` 是 NOT NULL 但没赋值，插入失败，Redis 里的座位就被锁死了。

**面试怎么讲**：不要只说"我调了个 Lua 脚本"，要说"脚本负责原子性，Java 负责业务规则和失败补偿"。

### 11.7 下单：Redis 先成交，数据库后落库

文件：[TicketOrderServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/TicketOrderServiceImpl.java)（`createOrder()`，约 127 行）

```java
// 1. 用锁座凭证换座位索引
String lockedIndexes = redisCache.get(tokenKey, String.class);
if (StrUtil.isBlank(lockedIndexes)) throw new ShowGoFrameException(BaseCode.SEAT_LOCK_EXPIRED);

// 2. Redis 原子成交：锁定 -> 已售
SeatOperateResult confirmResult = confirmSeats(...);

// 3. 数据库事务：订单 + 路由 + 本地消息表 + 对账流水
try {
    ((ITicketOrderService) AopContext.currentProxy())
            .createOrderTx(order, traceId, JSON.toJSONString(message), messageUuid);
} catch (Exception e) {
    seatRedisData.rollbackSeats(...);   // 事务失败 -> 回滚 Redis（before/after 反过来传）
    throw e;
}
```

**三个细节**

1. `AopContext.currentProxy()`：`createOrderTx` 上的 `@Transactional` 只有走代理才生效，
   同一个类里直接 `this.createOrderTx()` 会绕过事务；
2. 事务里一次写四样东西（见下一段），这是 Outbox 的核心；
3. 回滚时 `beforeQty/afterQty` 要**反过来传**：成交时是 `688 → 687`，回滚就是 `687 → 688`。

### 11.8 `createOrderTx()` + Outbox：消息为什么不会丢

```java
@Transactional(rollbackFor = Exception.class)
public void createOrderTx(TicketOrder order, Long traceId, String payload, String messageUuid) {
    save(order);                                   // 订单
    ticketOrderRouterMapper.insert(...);           // 订单路由
    localMessageService.savePending(order.getId(),
            BIZ_TYPE_TICKET_ORDER, topic, payload, messageUuid);  // 本地消息表（PENDING）
    ticketReconcileLogService.saveReconcileLog(...);              // 对账流水
}
```

四条记录在数据库里是一致的：要么全成功，要么全回滚。**只要订单存在，消息就一定存在于 Outbox 里。**

投递器 [LocalMessageServiceImpl.dispatchPending()](../showgo-core-service/src/main/java/org/javaup/service/impl/LocalMessageServiceImpl.java)：

```java
@Scheduled(fixedDelayString = "${ticket.local-message.scan-interval-ms:2000}")
public int dispatchPending() {
    for (LocalMessage message : pending) {
        try {
            dispatch(message);          // 发 Kafka
            markSent(message.getId());  // -> SENT
        } catch (Exception e) {
            onDispatchFailure(message, e); // 指数退避；超过上限 -> FAILED
        }
    }
}
```

`dispatch()` 里有两个关键赋值：

```java
extend.setUuid(message.getMessageUuid());          // 消费端用它做幂等
extend.setProducerTime(...message.getCreateTime()); // 消费端用它判断消息是否延迟
```

**如果没有本地消息表**：数据库提交后进程崩溃，消息就不会发出，订单永远没有票；
**如果直接发 Kafka 再写库**：可能消息到了、订单事务却失败，消费端处理一条不存在的订单。

### 11.9 消费端：延迟丢弃、幂等、失败回滚

文件：[TicketOrderConsumer.java](../showgo-core-service/src/main/java/org/javaup/kafka/consumer/TicketOrderConsumer.java)

**① 消费前：消息延迟超过阈值就丢弃并回滚**

```java
long delay = System.currentTimeMillis() - message.getProducerTime().getTime();
if (delay > messageDelayThresholdMs) {
    seatRedisData.rollbackSeats(...);   // 座位还回去
    return false;                        // 不消费
}
```

**② 消费幂等**

```java
@RepeatExecuteLimit(name = CREATE_TICKET_ORDER, keys = {"#message.uuid"})
```

再加上数据库里的判断：如果这批座位已经关联了本订单，直接返回，不重复插票。

**③ 消费失败：回滚 Redis，但要区分"订单已存在"**

```java
SeatQuotaOperate operate = SeatQuotaOperate.YES;
if (throwable instanceof ShowGoFrameException e
        && e.getCode().equals(BaseCode.TICKET_ORDER_EXIST.getCode())) {
    operate = SeatQuotaOperate.NO;   // 幂等命中，座位状态是对的，不能回滚
}
```

**为什么这个区分很重要**：重复消费时如果无脑回滚，会把一个已经正常售出的座位重新变成可售，直接造成超卖。

### 11.10 延迟队列：锁座超时 vs 支付超时

文件：[ConsumerDelayedSeatRelease.java](../showgo-core-service/src/main/java/org/javaup/delay/consumer/ConsumerDelayedSeatRelease.java)

```java
if ("LOCK".equals(message.getType())) {
    handleLockTimeout(message);
} else if ("ORDER".equals(message.getType())) {
    ticketOrderService.handleOrderPayTimeout(message);
}
```

锁座超时的判定很巧：

```java
String indexes = redisCache.get(tokenKey, String.class);
if (StrUtil.isBlank(indexes)) {
    return;   // 凭证没了 = 已经下单或已经释放过
}
seatService.release(...);
```

**用"凭证是否还在"来做幂等**，不需要额外记录"是否已处理"。
支付超时则判断订单是否仍为"待支付"，逻辑在 `handleOrderPayTimeout()`。

**如果延迟消息本身丢了**：对账任务会兜底（扫 `expire_time < now` 的待支付订单），座位不会永远被占。

### 11.11 对账：三类差异与 30 秒窗口

文件：[TicketReconcileTaskServiceImpl.java](../showgo-core-service/src/main/java/org/javaup/service/impl/TicketReconcileTaskServiceImpl.java)

任务每 5 分钟跑一次，逐场次处理三类问题：

| 方法 | 发现什么 | 怎么修 |
| --- | --- | --- |
| `reconcileRedisDeductWithoutOrder` | Redis 有扣减流水，但 DB 没有对应订单 | 回滚座位 + 删流水 |
| `reconcileOrderWithSeatDetail` | 订单已建，但座位明细没落成已售 | 标记异常 + 以 DB 为准重建座位图 |
| `closeTimeoutOrders` | 待支付订单已过 `expire_time` | 兜底关单 + 释放座位 |

为什么等 30 秒再判定：

```java
if (traceLog.getTs() != null && now - traceLog.getTs() < TOLERANCE_MILLIS) {
    continue;   // 消息可能还在投递途中
}
```

**窗口太小**：会把正在途中的正常订单误判成异常去回滚（灾难）；
**窗口太大**：差异发现得慢。当前取 30 秒，是投递器 2 秒扫描周期的 15 倍，留足余量。

### 11.12 缓存读链路：为什么要四道门

文件：[ShowSessionServiceImpl.queryDetail()](../showgo-core-service/src/main/java/org/javaup/service/impl/ShowSessionServiceImpl.java)

```java
// ① L1 本地缓存
SessionDetail local = sessionLocalCache.get(cacheKey.getRelKey());
if (local != null) return local;
// ② L2 Redis
SessionDetail cached = redisCache.get(cacheKey, SessionDetail.class);
if (cached != null) { sessionLocalCache.put(...); return cached; }
// ③ 布隆过滤器：拦掉绝大多数不存在的 id
if (!bloom.contains(sessionId)) throw ...;
// ④ 空值缓存：防止同一个非法 id 反复打 DB
if (redisCache.hasKey(nullKey)) throw ...;
// ⑤ 加锁 + 双重检查 + 回源 DB
RLock lock = serviceLockTool.getLock(...);
lock.lock();
try {
    cached = redisCache.get(cacheKey, SessionDetail.class);  // 双重检查
    if (cached != null) return cached;
    ShowSession session = getById(sessionId);
    ...
} finally { lock.unlock(); }
```

每一层各挡一种问题：

| 层 | 挡什么 |
| --- | --- |
| L1 本地缓存 | 网络往返（热点数据） |
| L2 Redis | DB 压力 |
| 布隆过滤器 | 缓存穿透（非法 id） |
| 空值缓存 | 穿透漏网的非法 id 反复打 DB |
| 分布式锁 + 双重检查 | 缓存击穿（热点 key 同时失效） |

**注意**：座位图**不进本地缓存**，因为它要求强一致，本地缓存会导致"看到可售、点进去已被抢"。

### 11.13 管理端发布场次：一个事务 + afterCommit

文件：[AdminServiceImpl.createSession()](../showgo-core-service/src/main/java/org/javaup/service/impl/AdminServiceImpl.java)（约 220 行）

```java
@Transactional(rollbackFor = Exception.class)
public ShowSession createSession(AdminSessionCreateDto createDto) {
    showSessionService.save(session);        // 场次
    seatAreaMapper.insert(area);             // 分区（广播表，ShardingSphere 自动双写）
    showSeatService.saveBatch(seats, 500);   // 批量生成座位

    afterCommit(() -> {
        bloom.add(showId); bloom.add(sessionId);          // 布隆
        sessionCacheInvalidator.publishInvalidate(...);   // 失效 C 端缓存
        showSessionService.warmUpSeatMap(sessionId);      // 预热座位图
    });
    return session;
}
```

**为什么缓存动作必须放在 `afterCommit` 里**：事务还没提交时，`warmUpSeatMap()` 去读数据库可能读不到新场次，
预热出来的座位图就是空的。放到提交后执行，才能读到完整数据。

**顺序也有讲究**：先加布隆（否则 `queryDetail` 会直接判"场次不存在"）→ 再失效旧缓存 → 最后预热。

**这段代码的价值**：管理端发布后，用户端**不需要重启服务**就能立刻看到演出并选座。

### 11.14 鉴权：为什么管理端不和用户共用登录态

文件：[MvcConfig.java](../showgo-core-service/src/main/java/org/javaup/config/MvcConfig.java)

```java
registry.addInterceptor(new AdminAuthInterceptor(redisCache))
        .addPathPatterns("/admin/**")
        .excludePathPatterns("/admin/login")
        .order(1);

registry.addInterceptor(new LoginInterceptor())
        .excludePathPatterns(..., "/admin/**")
        .order(2);
```

管理端 token 存在 Redis（`admin:token:{token}`，默认 2 小时），和 C 端的用户会话完全隔离：
普通用户 token 不能调管理接口，管理员也不需要先登录用户端。

### 11.15 读代码的三条方法

1. **顺着一次请求读**：Controller → Service → Lua / Mapper → Kafka，不要从目录结构开始乱翻。
2. **每个写操作都问"失败谁补偿"**：Redis 写完 DB 失败、DB 写完消息没发、消息发了消费失败——每种都要有答案。
3. **每个读操作都问"缓存在哪几层、什么时候失效"**：否则面试一问"改了数据为什么页面没变"就答不上来。
