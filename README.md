# 星幕票务 ShowGo · 演出赛事在线选座购票系统

> 一个围绕「开票瞬间的选座争抢」构建的高并发票务系统。
> Redis BitField 表达座位图、Lua 保证选座原子性、本地消息表 + Kafka 保证出票可靠、
> 延迟队列负责锁座与支付超时、定时对账收敛缓存与数据库的差异。

`Spring Boot 3` · `JDK 17` · `ShardingSphere` · `Redis / Redisson` · `Kafka` · `MyBatis-Plus` · `Vue 3`

---

## 一、为什么做这个项目

演出票务和普通电商秒杀最大的区别是：**卖的不是数量，是位置**。

一场 600 座的演出能卖出 600 张票，但「谁坐在第 3 排正中间」这件事必须唯一且不可重复。
这带来四个普通秒杀系统不会遇到的问题：

| 问题 | 本项目的解法 |
| --- | --- |
| 库存粒度从「张」下沉到「座位」，如何高效表达几万个座位的实时状态？ | Redis BitField 位图，每座位 2bit，5 万座位仅约 12.5KB |
| 用户要买「连座」，怎么在并发下保证选中的座位一定连续且都属于自己？ | Lua 脚本单次往返完成状态校验、连续性校验与原子占位 |
| 用户选完座不付款，座位不能一直占着，怎么自动回收？ | 延迟队列 + 锁座凭证，超时自动释放并触发候补补位 |
| 缓存里的座位状态和数据库里的票不一致怎么办？ | 本地消息表可靠投递 + 定时对账 + 差异补偿 + 兜底关单 |

项目完整实现了「浏览演出 → 选场次 → 选座 → 锁座 → 下单 → 支付出票 → 入场核销 → 退票回流」的闭环。

---

## 二、技术栈

| 分类 | 选型 |
| --- | --- |
| 框架 | Spring Boot 3.5.4、JDK 17、MyBatis-Plus 3.5.7 |
| 存储 | MySQL + ShardingSphere 5.3.2（2 库 × 2 表）、Redis、Caffeine（L1 本地缓存） |
| 分布式 | Redisson 3.52（读 / 写 / 公平 / 可重入锁）、自定义分布式延迟队列 |
| 消息 | Kafka（发布确认、手动 ACK、死信队列） |
| 治理 | 令牌桶 / 滑动窗口限流、一次性访问令牌、布隆过滤器、本地消息表、幂等注解 |
| 可观测 | Actuator + Micrometer + Prometheus |
| 前端 | Vue 3 + Vite + Element Plus + Pinia + SSE |

---

## 三、架构总览

### 3.1 分层

```
┌────────────────────────────────────────────────────────────┐
│  前端 showgo-vue3（Vue3 + Element Plus + SSE 实时座位图）    │
└───────────────────────────┬────────────────────────────────┘
                            │ /api → 8085
┌───────────────────────────▼────────────────────────────────┐
│  showgo-core-service（唯一可运行服务）                       │
│  controller → service → mapper / lua / kafka / delay        │
└───────────────────────────┬────────────────────────────────┘
                            │
   ┌────────────┬───────────┼───────────┬────────────┬──────────────┐
   ▼            ▼           ▼           ▼            ▼              ▼
 Redis       MySQL      Kafka      Redisson     Caffeine     Prometheus
 座位图      分库分表   出票/广播   锁/延迟队列   本地缓存     指标
```

### 3.2 模块

| 模块 | 职责 |
| --- | --- |
| `showgo-common` | 返回码、业务枚举、常量、异常体系、`SpringUtil`、用户上下文 |
| `showgo-parameter` | 跨模块 DTO / VO |
| `showgo-sharding` | 分库分表依赖与 SnakeYAML 兼容补丁 |
| `showgo-redis-tool-framework` | Redis Key 规范、缓存读写抽象、限流（令牌桶 / 滑动窗口）、选座访问令牌 |
| `showgo-redisson-framework` | 分布式锁、幂等注解、布隆过滤器、**分片延迟队列** |
| `showgo-id-generator-framework` | 雪花算法 + Redis 分配 workId / dataCenterId |
| `showgo-mq-framework` | Kafka 生产 / 消费模板方法与成功失败钩子 |
| `showgo-core-service` | 演出、场次、座位、订单、票、候补的业务实现 |
| `showgo-vue3` | 前端工程 |
| `sql` | 分片库建表与初始化数据 |

---

## 四、核心链路

### 4.1 从选座到出票

```
用户点击座位
   │
   ├─ ① GET  /seat/map/{sessionId}    读取 Redis BitField 座位图（一次往返返回全场状态）
   ├─ ② POST /seat/recommend          智能连座推荐（Lua 内解码扫描连续空位）
   │
   ├─ ③ POST /seat/lock               锁座
   │     Lua：校验座位状态 / 连续性 / 限购 → 置为「锁定」→ 写入 lockToken（TTL 15 分钟）
   │     同时投递一条「锁座超时」延迟消息
   │
   ├─ ④ POST /ticket-order/create     下单
   │     限流 + 一次性访问令牌校验
   │     Lua：锁定 → 已售，扣减余票，写座位变更流水（返回 beforeQty / changeQty / afterQty）
   │     数据库事务：订单(待支付) + 订单路由 + 本地消息表(PENDING) + 对账流水
   │     事务失败 → 回滚 Redis 座位（指数退避重试，耗尽后落库并告警）
   │
   ├─ ⑤ 本地消息表投递器（每 2 秒扫描）→ Kafka
   │     消费端幂等（消息 uuid）→ 座位明细落库 → 场次余票扣减 → 热销榜与场馆买家统计
   │     消费成功 → 本地消息置为 ACKED
   │     消息滞留超阈值 / 消费失败 → 回滚 Redis 座位 + 写对账流水
   │
   └─ ⑥ POST /ticket-order/pay        支付出票（一票一座）
```

### 4.2 取消 / 退票 / 超时

```
支付超时 ──延迟队列──► 订单置「已超时」→ 座位回「可售」→ 余票回补 → 候补自动补位
主动取消 ────────────► 订单置「已取消」→ 同上
申请退票 ────────────► 票置「已退票」→ 订单置「已退款」→ 座位回流 → 候补自动补位
锁座超时 ──延迟队列──► 校验 lockToken 是否仍存在（存在 = 未下单）→ 释放座位
```

---

## 五、技术亮点

### 5.1 用 BitField 表达座位图

每个座位占用 **2 bit**，编码四种状态：

| 值 | 含义 |
| --- | --- |
| `0` | 可售 |
| `1` | 锁定中（已选座未下单） |
| `2` | 已售 |
| `3` | 不可用（舞台遮挡、设备位） |

```
座位索引 index → 位偏移 = index * 2
状态         = (byte >> (6 - bitOffset % 8)) & 3
```

一场 5 万座位的演出，位图约 **12.5KB**；若用 Hash 存储 5 万个 field 需要数 MB。
读取时只做**一次 GET**，在 Lua 内解码出整场状态，避免为每个座位发起一次 `BITFIELD GET` 往返。

### 5.2 Lua 保证选座原子性

`seatLock.lua` 在一次 Redis 调用内完成：

1. 读取整场位图并在 Lua 内解码，校验所选座位是否都可售；
2. 校验座位**连续**（相邻索引差值为 1）；
3. 校验用户在该场次的**限购额度**（Hash 计数，锁定与已购累计）；
4. 原子地把这些座位位置为「锁定」，写入 `座位索引 → lockToken`；
5. 写入锁座凭证并设置 TTL。

下单时 `seatConfirm.lua` 会**二次校验座位归属**（`hget 座位索引 == 本次 lockToken`），
防止并发下把别人的锁座抢占过来，校验通过后才转为「已售」。

### 5.3 本地消息表 + Kafka 的可靠出票

订单落库与消息投递没有采用「写完库再发消息」这种会丢消息的做法，而是：

```java
@Transactional
public void createOrderTx(TicketOrder order, Long traceId, String payload, String messageUuid) {
    save(order);                                    // 订单
    ticketOrderRouterMapper.insert(...);            // 订单路由
    localMessageService.savePending(...);           // 本地消息表（PENDING）
    ticketReconcileLogService.saveReconcileLog(...);// 对账流水
}
```

后台投递器每 2 秒扫描 `PENDING / FAILED` 的消息发往 Kafka，
失败按 `2^n` 秒退避重试，超过上限置为 `FAILED` 等待人工介入。
消费端以消息 uuid 做幂等（`@RepeatExecuteLimit`），成功后再把本地消息置为 `ACKED`。

状态机：`PENDING → SENT → ACKED / FAILED`。

### 5.4 延迟队列闭环

锁座与支付超时都会投递延迟消息，到点后由 `ConsumerDelayedSeatRelease` 按 `type` 处理：

| 类型 | 判定依据 | 动作 |
| --- | --- | --- |
| `LOCK` | 锁座凭证是否还在 Redis | 存在 → 说明未下单，释放座位并回收额度 |
| `ORDER` | 订单状态是否仍为「待支付」 | 是 → 关单、释放座位、回补余票 |

两种超时天然幂等：凭证被删除说明已下单，订单已支付说明无需处理。
延迟队列按 `isolationRegionCount` 分片，并配合线程池异步消费。

### 5.5 定时对账与差异补偿

`TicketReconcileTaskServiceImpl` 默认每 5 分钟执行一次，逐场次处理三类差异：

1. **Redis 扣减流水存在、数据库订单不存在**（缓存扣了但订单没建成）
   → 超过 30 秒容忍窗口后回滚座位、清理流水，并重建座位图；
2. **订单已创建、座位明细未落成已售**（异步链路中断）
   → 标记订单与对账流水异常，以数据库为准重建 Redis 座位图；
3. **支付超时订单未被关闭**（延迟消息丢失）
   → 兜底关单并释放座位。

同时保留手工入口 `POST /ticket-order/reconcile/{sessionId}`。

### 5.6 限流与令牌前置授权

- 入口按 `场次 + IP + 用户` 维度限流，支持**令牌桶**与**滑动窗口**两种算法切换；
- 连续触发阈值可进入**临时封禁**（`ThresholdPenaltyPolicy` 扩展点）；
- 抢座前必须先领取**一次性访问令牌**（默认 30 秒有效），下单时用 Lua 原子校验并消费，
  把没有资格的流量挡在选座热链路之外。

### 5.7 售罄候补自动补位

场次售罄后用户可以登记候补，候补队列用 ZSet 按**加入时间**排序保证先到先得。
座位一旦被释放（退票 / 取消 / 超时），系统自动为队列中最靠前的用户锁定座位、
推送通知，并同样受「锁座超时释放」保护，避免补位后又被长期占用。

### 5.8 实时座位图

座位变更通过 Kafka 广播到所有实例（消费组带随机后缀，保证每个实例都能收到），
再由各实例通过 SSE 推送给正在选座的前端连接：

```
GET /seat/stream/{sessionId}   →  text/event-stream
event: seat-change             →  { sessionId, seatIndexes, status, changeType }
```

---

## 六、数据库设计

### 6.1 分片规则

2 个库（`showgo_0` / `showgo_1`）× 每表 2 张物理表 = 4 个分片。

| 逻辑表 | 分库键 | 分表键 | 说明 |
| --- | --- | --- | --- |
| `tb_show_session` | `id` | `id` | 场次内聚，座位图不跨库 |
| `tb_show_seat` | `session_id` | `session_id` | 座位明细与场次同分片 |
| `tb_ticket_order` | `user_id` | `user_id` | 分库分表统一用 `user_id`，「我的订单」不跨分片 |
| `tb_ticket_order_router` | `order_id` | `order_id` | 解决「按订单号查订单」的跨分片问题 |
| `tb_ticket` | `order_id` | `order_id` | 与订单同分片 |
| `tb_seat_reconcile_log` | `order_id` | `order_id` | 分片内对账 |
| `tb_local_message` | `biz_id` | `biz_id` | 与订单同分片，可与订单同事务写入 |
| `tb_user` / `tb_user_info` | `id` / `user_id` | 同左 | 用户维度 |
| `tb_user_phone` | `phone` | `phone` | 按手机号哈希，支撑登录点查 |

**广播表**（数据量小、需全量可见）：`tb_venue`、`tb_show`、`tb_seat_area`、`tb_seat_lock_log`、`tb_rollback_failure_log`。

> 查询订单时始终带上分片键 `user_id`（`where id = ? and user_id = ?`），避免主键查询退化成全分片路由。

### 6.2 关键表

| 表 | 作用 |
| --- | --- |
| `tb_show` / `tb_show_session` / `tb_seat_area` | 演出、场次、分区票档 |
| `tb_show_seat` | 座位明细，出票时落库，对账与明细查询的事实来源 |
| `tb_ticket_order` | 订单状态机：待支付 → 已支付 → 已出票 / 已取消 / 已超时 / 已退款 |
| `tb_ticket` | 一票一座，支持核销与退票 |
| `tb_local_message` | 本地消息表，保证「写库成功 = 消息最终被投递」 |
| `tb_seat_reconcile_log` | 座位变更流水，Redis 与数据库对账的依据 |
| `tb_seat_lock_log` | 锁座流水：锁定、下单、主动释放、超时释放 |
| `tb_rollback_failure_log` | 回滚重试耗尽后的失败记录，用于告警 |

---

## 七、Redis Key 设计

带 `{}` 的是 Redis Cluster 的 **HashTag**，保证同一场次的座位图、锁座、流水落在同一槽位，
Lua 才能在一次调用内原子操作多个 key。

| Key | 结构 | 用途 |
| --- | --- | --- |
| `ticket:seat:map:{sessionId}` | BitField | 座位图，每座位 2bit |
| `ticket:seat:lock:{sessionId}` | Hash | 座位索引 → lockToken |
| `ticket:seat:token:{sessionId}:{token}` | String | 锁座凭证（TTL 即锁座有效期） |
| `ticket:seat:user:{sessionId}` | Hash | 用户在该场次的锁定 + 已购数量（限购） |
| `ticket:seat:index:code:{sessionId}` | Hash | 座位索引 → 座位编码 |
| `ticket:seat:trace:{sessionId}` | Hash | 座位变更流水（对账用） |
| `ticket:session:stock:{sessionId}` | String | 场次余票计数 |
| `ticket:waitlist:zset:{sessionId}` | ZSet | 候补队列，score 为加入时间 |
| `ticket:session:hot:rank` | ZSet | 热销场次榜 |
| `ticket:access:token:{sessionId}:{userId}` | String | 一次性选座令牌 |
| `ticket:limit:* / ticket:block:*` | — | 限流计数、临时封禁、违规计数 |
| `cache:show:* / cache:session:{*}` | String | 演出 / 场次缓存与空值缓存 |

---

## 八、接口清单

### 演出与场馆（免登录）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/show/list` | 演出列表（分类 / 城市 / 关键词 / 分页） |
| GET | `/show/{id}` | 演出详情（含全部场次） |
| GET | `/show/hot/sessions` | 热销场次榜 |
| GET | `/show/categories` | 演出分类 |
| GET | `/venue/cities` | 城市列表 |
| GET | `/venue/nearby` | 附近场馆（Redis GEO） |

### 选座

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/seat/map/{sessionId}` | 座位图（分区、票档、状态矩阵） |
| POST | `/seat/recommend` | 智能连座推荐 |
| POST | `/seat/lock` | 锁座，返回 lockToken |
| GET | `/seat/stream/{sessionId}` | SSE 实时座位变更 |
| POST | `/seat/waitlist/join` | 登记候补 |
| POST | `/seat/waitlist/cancel` | 取消候补 |
| GET / POST | `/seat/waitlist/status...` | 候补状态查询 |

### 订单与票

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/ticket-order/access-token/{sessionId}` | 领取一次性选座令牌 |
| POST | `/ticket-order/create` | 下单（需 lockToken + accessToken） |
| POST | `/ticket-order/pay` | 支付并出票 |
| POST | `/ticket-order/cancel` | 取消未支付订单 |
| POST | `/ticket-order/refund` | 退票 |
| GET | `/ticket-order/{orderId}` | 订单详情 |
| POST | `/ticket-order/my` | 我的订单 |
| POST | `/ticket-order/reconcile/{sessionId}` | 手动触发对账 |
| GET | `/ticket/my` | 我的票夹 |
| POST | `/ticket/check-in/{ticketId}` | 入场核销 |

### 用户

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/user/code` | 发送验证码（开发环境打印在后端日志） |
| POST | `/user/login` | 验证码登录，手机号首次登录自动注册 |
| GET / PUT | `/user/info` | 个人资料与会员等级 |

> 统一返回结构：`{ success, errorMsg, data, total }`。

### 管理端（B 端）

入口：**http://localhost:8080/admin/login**

默认账号 `admin` / `admin123`，可通过环境变量 `ADMIN_USERNAME` / `ADMIN_PASSWORD` 覆盖。
登录成功后签发 Redis token（默认 2 小时），管理端接口统一走 `/admin/**` 鉴权，与 C 端用户登录态隔离。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/admin/login` | 管理端登录 |
| POST | `/admin/logout` | 退出 |
| GET | `/admin/venues` | 场馆下拉 |
| GET | `/admin/shows` | 演出分页查询（关键词 / 状态） |
| POST | `/admin/shows` | 新增或编辑演出（有 id 即编辑） |
| PUT | `/admin/shows/{id}/status` | 上架 / 下架 / 已结束 |
| DELETE | `/admin/shows/{id}` | 删除演出（已有场次时拒绝，只能下架） |
| GET | `/admin/shows/{showId}/sessions` | 演出的场次列表 |
| POST | `/admin/sessions` | 创建场次：同一事务写场次 + 分区票档 + 批量座位 |
| PUT | `/admin/sessions/{id}/status` | 开售 / 停售(6) / 售罄 / 结束 / 取消 |
| POST | `/admin/sessions/{id}/stock/recompute` | 以座位明细为准重算余票并同步 Redis |

**创建场次后会自动完成三件事**：写入 Redis BitField 座位图与索引映射、把演出/场次加入布隆过滤器、
失效 C 端缓存。因此管理端刚发布的演出，用户端刷新即可看到并可立即选座，不需要重启服务。

---

## 九、快速开始

### 9.1 Docker 一键启动（推荐）

整套技术栈（MySQL + Redis + Kafka + 后端 + 前端）已经容器化，一条命令拉起：

```bash
docker compose up -d --build
```

启动完成后访问 **http://localhost:8080**（前端 Nginx 会把 `/api` 反向代理到后端）。

启动顺序由健康检查自动编排：

```
MySQL / Redis / Kafka 健康  →  kafka-init 预建 topic  →  backend 健康  →  frontend
```

| 服务 | 容器名 | 宿主机端口 | 说明 |
| --- | --- | --- | --- |
| 前端（Nginx） | showgo-frontend | 8080 | 访问入口 |
| 后端（Spring Boot） | showgo-backend | 8085 | 也可直接调接口 |
| MySQL | showgo-mysql | 13306 | 仅绑定 127.0.0.1，默认避开本机已装的 3306 |
| Redis | showgo-redis | 16379 | 仅绑定 127.0.0.1，默认避开本机已装的 6379 |
| Kafka | showgo-kafka | 29092 | 容器内用 `kafka:9092` |

> 后端容器通过 Docker 内网访问中间件（`mysql:3306`、`redis:6379`、`kafka:9092`），
> 所以宿主机的端口映射只是为了方便你用客户端连进去看数据，改端口不影响应用。

首次启动会在容器里完成 Maven 打包与前端构建，需要几分钟；MySQL 的数据卷第一次初始化时
会自动执行 `sql/` 下的三个脚本，建好两个分片库与全部表。

常用命令：

```bash
docker compose ps                    # 查看状态
docker compose logs -f backend       # 查看后端日志（验证码也在这里）
docker compose restart backend       # 只重启后端
docker compose down                  # 停止（保留数据）
docker compose down -v               # 停止并清空 MySQL/Redis/Kafka 数据卷
```

**端口冲突怎么办**：把 `.env.example` 复制成 `.env`，改掉对应端口再启动：

```bash
cp .env.example .env
# 例如本机其他服务占用了 8080：编辑 .env 改成 FRONTEND_PORT=8090
docker compose up -d
```

**容器内的配置说明**

| 配置 | 位置 | 说明 |
| --- | --- | --- |
| MySQL / Redis / Kafka 地址 | `docker-compose.yml` 的 `environment` | 通过 `REDIS_HOST`、`KAFKA_BOOTSTRAP_SERVERS` 等环境变量注入 |
| 分片数据源 | `docker/shardingsphere.docker.yaml` | 构建镜像时覆盖 jar 内的同名文件，数据源主机指向 `mysql` |
| 前端反向代理 | `docker/nginx.conf` | `/api` 转发到后端，并**关闭缓冲**保证 SSE 实时座位图可用 |
| 构建上下文排除 | `.dockerignore` | 排除 `target/`、`node_modules/`、`.git/`，加快构建 |

> 若要修改容器里的数据库连接，改 `docker/shardingsphere.docker.yaml` 后重新 `docker compose up -d --build`。

### 9.2 本地运行：依赖中间件

| 组件 | 版本 | 端口 |
| --- | --- | --- |
| MySQL | 8.x | 3306 |
| Redis | 6.x+ | 6379 |
| Kafka | 3.x | 9092 |

### 9.3 本地运行：初始化数据库

```bash
mysql -uroot -proot < sql/1_create_database.sql
mysql -uroot -proot < sql/showgo_0.sql
mysql -uroot -proot < sql/showgo_1.sql
```

脚本会创建 2 个分片库、全部分片表与广播表，并写入 5 个场馆、6 场演出、
8 个场次及其座位图（每个场次 688 个座位）。

### 9.4 本地运行：启动后端

数据库、Redis、Kafka 地址在 `showgo-core-service/src/main/resources/` 下配置：

- `application.yml`：端口 8085、Redis、Kafka、业务参数
- `shardingsphere.yaml`：2 库 × 2 表的分片规则

```bash
mvn clean install -DskipTests
java -jar showgo-core-service/target/showgo-core-service-0.0.1-SNAPSHOT.jar
```

启动时会自动完成：场馆 GEO 初始化 → 演出 / 场次布隆过滤器初始化 → 在售场次座位图预热。

### 9.5 本地运行：启动前端

```bash
cd showgo-vue3
npm install
npm run dev
```

前端默认通过 `/api` 代理到 `http://localhost:8085`（见 `vite.config.js`）。

### 9.6 演示路径

1. 打开首页，按分类 / 城市筛选，进入任意演出详情；
2. 选择一个「售票中」的场次，进入座位图；
3. 点击「智能推荐连座」，系统自动挑出连续空位；
4. 锁定座位 → 提交订单并支付 → 跳转票夹查看座位号；
5. 在订单页取消或退票，观察座位回流与候补补位。

管理端演示：打开 `/admin/login`（admin / admin123）→ 新建演出 → 进入「场次」→ 新建场次
（配置 2 个分区，系统自动生成座位并预热座位图）→ 回到用户端刷新，即可看到新演出并选座。

> 演示账号：手机号 `13800000001` ~ `13800000006`，验证码请查看后端日志。

### 9.7 我需要单独启动 ShowGoApplication 吗？

**Docker 模式下不需要。** 容器 `showgo-backend` 的启动命令就是
`java -jar app.jar`，而那个 jar 的主类正是 `ShowGoApplication`：

```bash
docker compose logs backend | grep "Started ShowGoApplication"
# showgo-backend | Started ShowGoApplication in 31.876 seconds
```

只有一种情况需要在 IDE 里启动它：你想打断点调试。做法是让本机的 Spring Boot
去连容器里的中间件（两者**不能同时启动**，都会占用 8085）：

```bash
docker compose stop backend        # 先释放 8085
```

然后把连接地址改成宿主机映射端口（默认值见 9.1 的端口表）：

| 组件 | 本机地址 | 说明 |
| --- | --- | --- |
| MySQL | `127.0.0.1:13306` | 注意不是 3306，本机 3306 可能是你自己装的 MySQL |
| Redis | `127.0.0.1:16379` | |
| Kafka | `127.0.0.1:29092` | |

需要改两处：

1. `showgo-core-service/src/main/resources/shardingsphere.yaml`：把 `mysql:3306` 改成 `127.0.0.1:13306`；
2. IDEA 的 Run Configuration 里加环境变量：

```
REDIS_HOST=127.0.0.1;REDIS_PORT=16379;KAFKA_BOOTSTRAP_SERVERS=127.0.0.1:29092
```

> 改源码里的 `shardingsphere.yaml` 不会影响容器：镜像构建时
> `docker/Dockerfile.backend` 会用 `docker/shardingsphere.docker.yaml` 覆盖它。
> 调试完执行 `docker compose start backend` 恢复容器后端即可。

---

## 十、目录结构

```
showgo
├── showgo-common                       公共枚举、常量、异常
├── showgo-parameter                    跨模块 DTO / VO
├── showgo-sharding                     分库分表依赖
├── showgo-id-generator-framework       雪花 ID
├── showgo-mq-framework                 Kafka 生产 / 消费模板
├── showgo-redis-tool-framework         Redis 封装与限流
├── showgo-redisson-framework           分布式锁、幂等、布隆过滤器、延迟队列
├── showgo-core-service                 业务核心
│   └── src/main
│       ├── java/org/javaup
│       │   ├── controller              接口
│       │   ├── service                 业务（座位、订单、候补、对账）
│       │   ├── lua                     Lua 脚本 Java 封装
│       │   ├── kafka                   出票消费、座位广播、缓存失效
│       │   ├── delay                   延迟队列消费者
│       │   ├── init                    启动初始化与座位图预热
│       │   └── stream                  SSE 实时推送
│       └── resources
│           ├── lua                     座位图读写 / 锁座 / 下单 / 回滚 / 推荐
│           ├── shardingsphere.yaml     分片规则
│           └── application.yml         业务配置
├── showgo-vue3                         前端工程
├── sql                                 建库与初始化脚本
├── docker                              Docker 构建文件
│   ├── Dockerfile.backend              后端多阶段构建（容器内 Maven 打包）
│   ├── Dockerfile.frontend             前端构建 + Nginx 托管
│   ├── nginx.conf                      反向代理 /api 与 SSE 透传
│   └── shardingsphere.docker.yaml      容器专用分片配置（数据源指向 mysql）
├── docker-compose.yml                  一键启动整套技术栈
├── .env.example                        端口等可覆盖变量示例
└── .dockerignore                       构建上下文排除项
```

---

## 十一、状态约定

### 座位状态

| 值 | 含义 | 可流转到 |
| --- | --- | --- |
| 0 | 可售 | 1（锁座）、3（下架） |
| 1 | 锁定中 | 2（下单）、0（超时 / 取消） |
| 2 | 已售 | 0（退票） |
| 3 | 不可用 | — |

### 订单状态

```
待支付 ──支付──► 已出票 ──退票──► 已退款
   │
   ├──超时──► 已超时
   └──取消──► 已取消
```

---

## 十二、主要配置项

```yaml
ticket:
  seat:
    lock-seconds: 900          # 锁座有效期
    max-seat-per-order: 6      # 单笔最多购票数
    max-seat-per-user: 6       # 单场次限购
  order:
    pay-timeout-seconds: 900   # 支付超时
  access:
    token:
      enabled: true
      ttl-seconds: 30
  waitlist:
    notify-dedup-seconds: 1800
  reminder:
    advance-minutes: 30        # 开售前提醒
  local-message:
    scan-interval-ms: 2000     # 本地消息表扫描间隔
    max-retry: 6
  rollback:
    retry:
      max-attempts: 3
  reconcile:
    cron: 0 */5 * * * ?        # 对账任务
```

---

## 十三、后续可扩展

- **座位图分片**：超大型场馆可按区域把座位图拆成多个 key，降低单 key 大小；
- **实名制与入场核验**：票与实名信息绑定，入场时校验；
- **动态票价**：按库存水位与时间窗口浮动定价；
- **个性化推荐**：基于用户历史分区偏好推荐座位；
- **压测与容量报告**：对「开票瞬间同区域连座争抢」做阶梯压测，输出 P99 与零超卖的证据。
