# 面试前的项目测试清单（ShowGo）

> 目标不是"点一遍能跑"，而是**每条结论都有证据、每个异常都能解释**。
> 面试官问"你怎么验证的"，你能直接说出用例、命令和实测结果。

---

## 0. 测试原则

| 原则 | 说明 |
| --- | --- |
| 先主链路，后边界 | 主链路跑通才谈得上异常测试 |
| 每条用例留证据 | 截图 / 命令输出 / 日志片段，至少留一样 |
| 异常比正常重要 | 面试官真正关心的是"出错时会发生什么" |
| 数字必须自测 | 并发、内存、延迟这些数字不要编，脚本会给你 |

---

## 1. 环境准备（约 3 分钟）

```powershell
cd D:\Myproject11\后端\showgo
docker compose up -d
docker compose ps                    # 5 个服务都应 healthy
```

确认入口：

```powershell
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8085/actuator/health
```

浏览器打开 **http://localhost:8080**。

---

## 2. 第一轮：自动化冒烟（3 分钟）

```powershell
node tools/smoke-test.mjs
```

脚本会自动完成并逐条打印 PASS/FAIL：

1. 后端健康检查
2. 演出列表（免登录）
3. 发送验证码 + 登录（自动从后端日志解析验证码）
4. 读取座位图
5. 锁座 2 个连座
6. 重复锁同一座位（应被拒绝）
7. 单笔超过 6 张（应被拒绝）
8. 未开售场次锁座（应被拒绝）
9. 无效访问令牌下单（应被拒绝）
10. 领取访问令牌 + 下单
11. 访问令牌复用（应被拒绝）
12. 支付出票
13. 票夹出现本次 2 张有效票
14. 座位图状态变为已售(2)
15. 退票
16. 座位回流为可售(0)、余票恢复
17. 打印座位图 Redis 内存占用（面试可展示）

> 如果提示"无法解析验证码"，用 `TEST_CODE=123456 node tools/smoke-test.mjs` 手动指定。

---

## 3. 第二轮：功能逐项验收

### A. 浏览与查询（免登录）

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| A1 | 首页加载 | 出现分类、城市、热销、演出列表 | 截图 |
| A2 | 点分类筛选 | 列表只剩该分类 | 截图 |
| A3 | 点城市筛选 | 列表只剩该城市 | 截图 |
| A4 | 搜索关键词 | 命中演出名或艺人 | 截图 |
| A5 | 进入演出详情 | 展示场馆、简介、全部场次 | 截图 |
| A6 | 热销榜点击 | 进入对应场次座位图 | 截图 |

### B. 登录与用户

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| B1 | 未登录点"锁定座位" | 跳转登录 / 401 | 截图 |
| B2 | 发送验证码 | 成功提示；后端日志出现 `[登录验证码] phone=… code=…` | `docker compose logs backend \| Select-String "登录验证码"` |
| B3 | 错误验证码登录 | 提示验证码错误 | 截图 |
| B4 | 正确验证码登录 | 拿到 token，进入首页 | 截图 |
| B5 | 新手机号登录 | 自动注册（用户表+资料表+手机号映射） | 见下方 SQL |
| B6 | 修改个人资料 | 保存后刷新仍在 | 截图 |
| B7 | 退出登录 | 回到登录页，接口 401 | 截图 |

新用户落库检查：

```powershell
docker exec showgo-mysql mysql -uroot -proot -N -e "select count(*) from showgo_0.tb_user_0; select count(*) from showgo_0.tb_user_1;"
```

### C. 选座

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| C1 | 打开售票中场次座位图 | 分区、行号、座位渲染正常 | 截图 |
| C2 | 点空座位 | 变红（已选），底部显示座位号与金额 | 截图 |
| C3 | 点第二个不连续座位 | 提示"必须连续"或被清空 | 截图 |
| C4 | 点已售/锁定座位 | 提示不可选 | 截图 |
| C5 | 智能推荐连座 | 自动选中连续空位 | 截图 |
| C6 | 锁定座位 | 成功并显示 15 分钟倒计时/有效期 | 接口返回 `lockToken` |
| C7 | 重复锁定同一座位 | 提示"座位已被选走" | 截图 |
| C8 | 选 7 个座位 | 拒绝（单笔上限 6） | 截图 |
| C9 | 打开待开售场次锁座 | 提示"场次尚未开售" | 截图 |

实时座位图（双窗口验证）：

1. 浏览器 A 打开某场次座位图；
2. 浏览器 B（或 `curl`）锁定同一场次的另一个座位；
3. A 无需刷新，对应座位自动变灰 → 证明 SSE + Kafka 广播链路生效。

### D. 下单与支付

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| D1 | 提交订单 | 生成订单号，状态"待支付" | 订单页截图 |
| D2 | 支付 | 状态变"已出票"，票夹出现票 | 截图 |
| D3 | 重复点支付 | 幂等返回，不重复出票 | 接口返回状态 3 |
| D4 | 不支付等超时 | 订单变"已超时"，座位释放（把超时时间临时调小，见 6.2） | 截图 |
| D5 | 我的订单筛选 | 待支付/已出票/已退款分类正确 | 截图 |

### E. 取消与退票

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| E1 | 取消待支付订单 | 状态"已取消"，座位回到可售 | 座位图变蓝 |
| E2 | 退票（已出票） | 状态"已退款"，票变"已退票"，座位回流 | 票夹+座位图 |
| E3 | 退票后余票 | 余票数恢复 | 座位图顶部数值 |
| E4 | 候补自动补位 | 先登记候补，再退一张票 → 候补用户收到通知并被锁座 | 后端日志 `[WAITLIST_*]` |

### F. 运营与可观测

| # | 操作 | 预期 | 证据 |
| --- | --- | --- | --- |
| F1 | 买票后看热销榜 | 该场次销量上升 | 首页热销榜 |
| F2 | 查看 Prometheus 指标 | 能拿到指标 | `curl.exe -s http://localhost:8085/actuator/prometheus` |
| F3 | 手动触发对账 | 返回成功且日志有"对账完成" | `docker compose logs backend \| Select-String "对账"` |

---

## 4. 第三轮：边界与异常

| # | 用例 | 操作 | 预期 |
| --- | --- | --- | --- |
| X1 | 未登录调用下单 | 去掉 Authorization | 401 |
| X2 | 伪造 token | Authorization: abc | 401 |
| X3 | 无效锁座凭证 | `lockToken: "abc"` | "锁座已过期" |
| X4 | 无效访问令牌 | `accessToken=abc` | "令牌无效或已被使用" |
| X5 | 越权查订单 | 用用户 B 的 token 查用户 A 的 orderId | "订单不存在" |
| X6 | 重复下单 | 同一 lockToken 提交两次 | 第二次失败（凭证已消费） |
| X7 | 场次不存在 | `sessionId=999999` | "场次不存在" |
| X8 | 座位索引越界 | `seatIndexes: [999999]` | 座位不存在/失败 |

---

## 5. 第四轮：故障注入（面试最加分）

### 5.1 Kafka 宕机：验证本地消息表

```powershell
docker compose stop kafka
```

此时去下单（主流程应仍能成功，因为消息先落本地消息表）：

```powershell
docker exec showgo-mysql mysql -uroot -proot -N -e "select 'showgo_0.tb_local_message_0' t,status,retry_count from showgo_0.tb_local_message_0 union all select 'showgo_0.tb_local_message_1',status,retry_count from showgo_0.tb_local_message_1 union all select 'showgo_1.tb_local_message_0',status,retry_count from showgo_1.tb_local_message_0 union all select 'showgo_1.tb_local_message_1',status,retry_count from showgo_1.tb_local_message_1;"
```

预期：新消息 `status=0`（待投递），`retry_count` 随时间增加。

恢复：

```powershell
docker compose start kafka
# 等 10~30 秒后重查，status 应变 2（已确认）
```

> 可讲的坑：Kafka 不可用时 `KafkaTemplate.send` 会阻塞到 `max.block.ms`（默认 60 秒），
> 会占住投递线程；生产应把它调小并改成异步发送。

### 5.2 后端宕机：验证 Nginx 代理

```powershell
docker compose stop backend
curl.exe -s -o NUL -w "%{http_code}`n" http://localhost:8080/api/show/list   # 502
docker compose start backend
```

### 5.3 Redis 丢对账流水：验证对账回填

```powershell
# 1) 先买一张票，从对账流水表拿到 traceId
# 2) 删掉 Redis 里的这条流水
docker exec showgo-redis redis-cli HDEL "showgo-ticket:seat:trace:{1001}" <traceId>
# 3) 触发对账（或等 5 分钟定时任务）
curl.exe -s -X POST -H "Authorization: <token>" http://localhost:8080/api/ticket-order/reconcile/1001
# 4) 重新读 Redis，应已从 DB 回填
docker exec showgo-redis redis-cli HGETALL "showgo-ticket:seat:trace:{1001}"
```

### 5.4 重建后端容器：验证前端不会 502

```powershell
docker compose up -d --force-recreate backend
# 等 healthy 后，前端不需要重启
curl.exe -s -o NUL -w "%{http_code}`n" "http://localhost:8080/api/show/list?pageSize=1"   # 200
```

> 原理：nginx 用变量 + Docker 内置 DNS 做运行时解析，而不是启动时解析一次。

### 5.5 锁座超时释放（把时间调短再测）

先用临时环境变量把锁座有效期改成 30 秒：

```powershell
docker compose stop backend
$env:SEAT_LOCK_SECONDS=30
docker compose up -d backend
```

然后：锁定一个座位 → 不下单 → 等 30 秒 → 再看座位图，座位应回到可售，
日志出现 `锁座超时已释放`。

---

## 6. 第五轮：性能与容量

### 6.1 并发抢座（产出简历数字）

```powershell
node tools/seat-concurrency-test.mjs http://localhost:8080 1001 200 "<token>" same-seat 42
node tools/seat-concurrency-test.mjs http://localhost:8080 1001 200 "<token>" distinct-seat 100
```

期望：

- `same-seat`：成功恰好 1 次 → **零超卖**
- `distinct-seat`：成功 ≤ 6 → **限购生效**

脚本会输出吞吐（次/秒）与 P50/P95/P99。

### 6.2 内存对比（BitField vs Hash）

```powershell
docker exec showgo-redis redis-cli BITFIELD bench:bitmap SET u2 99998 0
docker exec showgo-redis redis-cli MEMORY USAGE bench:bitmap
docker exec showgo-redis redis-cli EVAL "for i=1,50000 do redis.call('hset','bench:hash',i,0) end return 1" 0
docker exec showgo-redis redis-cli MEMORY USAGE bench:hash
docker exec showgo-redis redis-cli DEL bench:bitmap bench:hash
```

期望：位图约 12.5KB，Hash 为 MB 级。

### 6.3 接口延迟（可选）

```powershell
1..50 | ForEach-Object { curl.exe -s -o NUL -w "%{time_total}`n" http://localhost:8080/api/seat/map/1001 }
```

观察平均/最大耗时；注意这是本机环回，不代表生产网络。

---

## 7. 证据留存模板

建议建一张表（Markdown 或 Excel），面试前填满：

| 用例 | 预期 | 实际 | 证据 | 结论 |
| --- | --- | --- | --- | --- |
| 并发抢同一座位 200 并发 | 成功 1 | 成功 1 | 脚本输出 | PASS |
| Kraft 停机后下单 | 消息待投递 | status=0 | SQL 输出 | PASS |
| …… | | | | |

面试时可以直接说："我做了 18 条冒烟用例，另外做了 4 组故障注入，其中 Kafka 停机后消息在恢复后 12 秒内完成确认。"

---

## 8. 两小时速通路线（时间紧就按这个顺序）

| 时间 | 内容 |
| --- | --- |
| 0:00–0:15 | 启动环境 + 跑 `smoke-test.mjs`，全部 PASS |
| 0:15–0:35 | 浏览器手工走一遍：首页 → 详情 → 选座 → 下单 → 支付 → 退票 |
| 0:35–0:55 | 并发脚本 + BitField 内存对比，记录数字 |
| 0:55–1:15 | 故障注入 5.1（Kafka 停机）与 5.4（后端重建） |
| 1:15–1:35 | 打开 Redis / MySQL 看关键数据，复述每张表的作用 |
| 1:35–2:00 | 按"现象→定位→根因→修复→加固"复述一次自己修过的 bug |

---

## 9. 已知限制（主动说出来比被问到强）

| 限制 | 现状 | 改进方向 |
| --- | --- | --- |
| 支付 / 短信 | 同步模拟，未接真实网关 | 接支付回调 + 幂等 + 渠道对账 |
| 压测 | 只做了正确性并发验证 | JMeter 阶梯压测，出 P99/吞吐 |
| 对账 | 全量遍历在售场次 | 分片增量扫描 + 差异工单 |
| Redis | 单实例 | Cluster + 主从切换后的座位图重建 |
| 锁座孤儿 | Redis 部分 key 丢失时依赖座位图整体 TTL 重建 | 对账增加"LOCKED 但无锁座凭证"扫描 |
| 消息顺序 | 出票消息未按 orderId 指定 key | 按 orderId 分区保证同单有序 |

---

## 附录：关键数据查询

```powershell
# 座位图内存
docker exec showgo-redis redis-cli MEMORY USAGE "showgo-ticket:seat:map:{1001}"
# 座位图前 4 个座位的状态（0可售 1锁定 2已售 3不可用）
docker exec showgo-redis redis-cli BITFIELD "showgo-ticket:seat:map:{1001}" GET u2 0 GET u2 2 GET u2 4 GET u2 6
# 锁座归属
docker exec showgo-redis redis-cli HGETALL "showgo-ticket:seat:lock:{1001}"
# 场次余票
docker exec showgo-redis redis-cli GET "showgo-ticket:session:stock:{1001}"
# 订单（按 user_id 分片，拿脚本输出的订单 id 替换）
docker exec showgo-mysql mysql -uroot -proot -N -e "select order_no,status,ticket_count,amount,seat_codes from showgo_1.tb_ticket_order_0 where user_id=10001 order by create_time desc limit 3;"
```
