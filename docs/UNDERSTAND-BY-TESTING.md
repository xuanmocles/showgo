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
