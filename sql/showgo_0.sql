-- =============================================================
-- 星幕票务 ShowGo 演出选座购票系统 —— 分片库 showgo_0
-- 分片规则（与 shardingsphere.yaml 保持一致）：
--   tb_show_session / tb_show_seat        按 session_id 分库分表
--   tb_ticket_order                       按 user_id 分库、session_id 分表
--   tb_ticket / tb_seat_reconcile_log     按 order_id 分库分表
--   tb_local_message                      按 biz_id（订单id）分库分表
--   tb_user / tb_user_info                按 id / user_id 分库分表
--   tb_user_phone                         按 phone 哈希分库分表
-- =============================================================
USE showgo_0;
SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- -------------------------------------------------------------
-- 广播表：每个分片库各存一份
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `tb_venue`;
CREATE TABLE `tb_venue` (
    `id` bigint NOT NULL COMMENT '场馆id',
    `name` varchar(128) NOT NULL COMMENT '场馆名称',
    `city` varchar(64) NOT NULL COMMENT '所在城市',
    `address` varchar(255) NOT NULL COMMENT '详细地址',
    `longitude` double DEFAULT NULL COMMENT '经度',
    `latitude` double DEFAULT NULL COMMENT '纬度',
    `venue_type` tinyint NOT NULL DEFAULT '1' COMMENT '1剧院 2体育馆 3livehouse 4体育场',
    `capacity` int NOT NULL DEFAULT '0' COMMENT '核定座位数',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1正常 0停用',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_city` (`city`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='场馆';

DROP TABLE IF EXISTS `tb_show`;
CREATE TABLE `tb_show` (
    `id` bigint NOT NULL COMMENT '演出id',
    `name` varchar(128) NOT NULL COMMENT '演出名称',
    `venue_id` bigint NOT NULL COMMENT '场馆id',
    `category` tinyint NOT NULL COMMENT '1演唱会 2话剧歌剧 3音乐节 4体育赛事 5脱口秀 6儿童亲子',
    `artist` varchar(128) DEFAULT NULL COMMENT '艺人/演出团体',
    `poster` varchar(512) DEFAULT NULL COMMENT '海报地址',
    `description` varchar(2048) DEFAULT NULL COMMENT '演出简介',
    `duration_minutes` int NOT NULL DEFAULT '120' COMMENT '演出时长(分钟)',
    `min_price` bigint NOT NULL DEFAULT '0' COMMENT '最低票价(分)',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1上架 2下架 3已结束',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_venue` (`venue_id`),
    KEY `idx_category` (`category`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出';

DROP TABLE IF EXISTS `tb_seat_area`;
CREATE TABLE `tb_seat_area` (
    `id` bigint NOT NULL COMMENT '分区id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `area_no` tinyint NOT NULL COMMENT '分区序号，从1开始',
    `area_code` varchar(16) NOT NULL COMMENT '分区编码，如 A/B/C',
    `area_name` varchar(64) NOT NULL COMMENT '分区名称',
    `area_type` tinyint NOT NULL DEFAULT '1' COMMENT '1普通 2VIP 3无障碍',
    `price` bigint NOT NULL COMMENT '票档单价(分)',
    `color` varchar(16) DEFAULT NULL COMMENT '前端座位图颜色',
    `row_count` int NOT NULL COMMENT '排数',
    `seat_per_row` int NOT NULL COMMENT '每排座位数',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='场次分区票档';

DROP TABLE IF EXISTS `tb_seat_lock_log`;
CREATE TABLE `tb_seat_lock_log` (
    `id` bigint NOT NULL COMMENT '主键',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `user_id` bigint NOT NULL COMMENT '用户id',
    `seat_codes` varchar(512) NOT NULL COMMENT '座位编码，逗号分隔',
    `lock_token` varchar(64) NOT NULL COMMENT '锁座令牌',
    `trace_id` bigint NOT NULL COMMENT '链路id',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1已锁定 2已下单 3主动释放 4超时释放',
    `expire_time` datetime NOT NULL COMMENT '锁座过期时间',
    `release_time` datetime DEFAULT NULL COMMENT '释放时间',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_session_user` (`session_id`,`user_id`),
    KEY `idx_expire` (`status`,`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='锁座流水';

DROP TABLE IF EXISTS `tb_rollback_failure_log`;
CREATE TABLE `tb_rollback_failure_log` (
    `id` bigint NOT NULL COMMENT '主键',
    `order_id` bigint DEFAULT NULL COMMENT '订单id',
    `user_id` bigint DEFAULT NULL COMMENT '用户id',
    `session_id` bigint DEFAULT NULL COMMENT '场次id',
    `detail` varchar(512) DEFAULT NULL COMMENT '失败明细',
    `result_code` int DEFAULT NULL COMMENT '回滚脚本返回码',
    `trace_id` bigint DEFAULT NULL COMMENT '链路id',
    `retry_attempts` int DEFAULT NULL COMMENT '重试次数',
    `source` varchar(64) DEFAULT NULL COMMENT '来源组件',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='座位回滚失败记录';

-- -------------------------------------------------------------
-- 分片表：_0 / _1 两个物理表
-- -------------------------------------------------------------
DROP TABLE IF EXISTS `tb_show_session_0`;
DROP TABLE IF EXISTS `tb_show_session_1`;
CREATE TABLE `tb_show_session_0` (
    `id` bigint NOT NULL COMMENT '场次id',
    `show_id` bigint NOT NULL COMMENT '演出id',
    `venue_id` bigint NOT NULL COMMENT '场馆id',
    `session_name` varchar(128) NOT NULL COMMENT '场次名称，如 2026-05-01 19:30',
    `begin_time` datetime NOT NULL COMMENT '开演时间',
    `end_time` datetime NOT NULL COMMENT '散场时间',
    `sale_begin_time` datetime NOT NULL COMMENT '开售时间',
    `sale_end_time` datetime NOT NULL COMMENT '停止售票时间',
    `seat_total` int NOT NULL DEFAULT '0' COMMENT '总座位数',
    `seat_available` int NOT NULL DEFAULT '0' COMMENT '剩余可售座位数',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1待开售 2售票中 3已售罄 4已结束 5已取消',
    `version` int NOT NULL DEFAULT '0' COMMENT '版本号',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_show` (`show_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出场次';
CREATE TABLE `tb_show_session_1` LIKE `tb_show_session_0`;

DROP TABLE IF EXISTS `tb_show_seat_0`;
DROP TABLE IF EXISTS `tb_show_seat_1`;
CREATE TABLE `tb_show_seat_0` (
    `id` bigint NOT NULL COMMENT '座位id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `area_id` bigint NOT NULL COMMENT '分区id',
    `area_code` varchar(16) NOT NULL COMMENT '分区编码',
    `row_no` int NOT NULL COMMENT '排号，从1开始',
    `col_no` int NOT NULL COMMENT '列号，从1开始',
    `seat_code` varchar(32) NOT NULL COMMENT '座位编码，如 A-03-12',
    `seat_type` tinyint NOT NULL DEFAULT '1' COMMENT '1普通 2VIP 3无障碍',
    `price` bigint NOT NULL COMMENT '票价(分)',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '0可售 1锁定 2已售 3不可用',
    `lock_token` varchar(64) DEFAULT NULL COMMENT '锁座令牌',
    `lock_user_id` bigint DEFAULT NULL COMMENT '锁座用户',
    `lock_expire_time` datetime DEFAULT NULL COMMENT '锁座过期时间',
    `order_id` bigint DEFAULT NULL COMMENT '成交订单id',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_session_seat` (`session_id`,`seat_code`),
    KEY `idx_session_status` (`session_id`,`status`),
    KEY `idx_lock_token` (`lock_token`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='场次座位';
CREATE TABLE `tb_show_seat_1` LIKE `tb_show_seat_0`;

DROP TABLE IF EXISTS `tb_ticket_order_0`;
DROP TABLE IF EXISTS `tb_ticket_order_1`;
CREATE TABLE `tb_ticket_order_0` (
    `id` bigint NOT NULL COMMENT '订单id',
    `order_no` varchar(32) NOT NULL COMMENT '对外订单号',
    `user_id` bigint NOT NULL COMMENT '下单用户id',
    `show_id` bigint NOT NULL COMMENT '演出id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `ticket_count` int NOT NULL COMMENT '票数',
    `amount` bigint NOT NULL COMMENT '订单金额(分)',
    `seat_codes` varchar(512) NOT NULL COMMENT '座位编码，逗号分隔',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1待支付 2已支付 3已出票 4已取消 5已退款 6已超时',
    `pay_type` tinyint DEFAULT NULL COMMENT '1余额 2支付宝 3微信',
    `expire_time` datetime NOT NULL COMMENT '支付截止时间',
    `pay_time` datetime DEFAULT NULL COMMENT '支付时间',
    `close_time` datetime DEFAULT NULL COMMENT '关闭时间',
    `refund_time` datetime DEFAULT NULL COMMENT '退票时间',
    `reconciliation_status` int NOT NULL DEFAULT '1' COMMENT '对账状态：1待处理 2异常 3不一致 4一致',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user` (`user_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='票务订单';
CREATE TABLE `tb_ticket_order_1` LIKE `tb_ticket_order_0`;

DROP TABLE IF EXISTS `tb_ticket_order_router_0`;
DROP TABLE IF EXISTS `tb_ticket_order_router_1`;
CREATE TABLE `tb_ticket_order_router_0` (
    `id` bigint NOT NULL COMMENT '主键',
    `order_id` bigint NOT NULL COMMENT '订单id',
    `user_id` bigint NOT NULL COMMENT '用户id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_order` (`order_id`),
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单路由表';
CREATE TABLE `tb_ticket_order_router_1` LIKE `tb_ticket_order_router_0`;

DROP TABLE IF EXISTS `tb_ticket_0`;
DROP TABLE IF EXISTS `tb_ticket_1`;
CREATE TABLE `tb_ticket_0` (
    `id` bigint NOT NULL COMMENT '票id',
    `order_id` bigint NOT NULL COMMENT '订单id',
    `user_id` bigint NOT NULL COMMENT '持票用户id',
    `show_id` bigint NOT NULL COMMENT '演出id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `seat_id` bigint NOT NULL COMMENT '座位id',
    `seat_code` varchar(32) NOT NULL COMMENT '座位编码',
    `area_name` varchar(64) NOT NULL COMMENT '分区名称',
    `price` bigint NOT NULL COMMENT '票价(分)',
    `status` tinyint NOT NULL DEFAULT '1' COMMENT '1有效 2已退票 3已入场',
    `check_in_time` datetime DEFAULT NULL COMMENT '入场核销时间',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_order` (`order_id`),
    KEY `idx_user` (`user_id`,`status`),
    KEY `idx_session_seat` (`session_id`,`seat_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='演出票';
CREATE TABLE `tb_ticket_1` LIKE `tb_ticket_0`;

DROP TABLE IF EXISTS `tb_seat_reconcile_log_0`;
DROP TABLE IF EXISTS `tb_seat_reconcile_log_1`;
CREATE TABLE `tb_seat_reconcile_log_0` (
    `id` bigint NOT NULL COMMENT '主键',
    `order_id` bigint NOT NULL COMMENT '订单id',
    `session_id` bigint NOT NULL COMMENT '场次id',
    `user_id` bigint NOT NULL COMMENT '用户id',
    `seat_codes` varchar(512) DEFAULT NULL COMMENT '涉及的座位编码',
    `trace_id` bigint NOT NULL COMMENT '链路id',
    `log_type` int NOT NULL COMMENT '记录类型：-1扣减 1恢复',
    `business_type` int NOT NULL COMMENT '业务类型：1成功 2超时 3失败 4主动取消 5退票',
    `before_qty` int DEFAULT NULL COMMENT '变更前余票',
    `change_qty` int DEFAULT NULL COMMENT '变更数量',
    `after_qty` int DEFAULT NULL COMMENT '变更后余票',
    `detail` varchar(512) DEFAULT NULL COMMENT '描述',
    `reconciliation_status` int NOT NULL DEFAULT '1' COMMENT '对账状态',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    KEY `idx_order_trace` (`order_id`,`trace_id`),
    KEY `idx_session` (`session_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='座位变更对账流水';
CREATE TABLE `tb_seat_reconcile_log_1` LIKE `tb_seat_reconcile_log_0`;

DROP TABLE IF EXISTS `tb_local_message_0`;
DROP TABLE IF EXISTS `tb_local_message_1`;
CREATE TABLE `tb_local_message_0` (
    `id` bigint NOT NULL COMMENT '主键',
    `message_uuid` varchar(64) NOT NULL COMMENT '消息唯一id，用于消费幂等',
    `biz_id` bigint NOT NULL COMMENT '业务id（订单id）',
    `biz_type` varchar(32) NOT NULL COMMENT '业务类型',
    `topic` varchar(128) NOT NULL COMMENT '目标topic',
    `payload` text NOT NULL COMMENT '消息体',
    `status` tinyint NOT NULL DEFAULT '0' COMMENT '0待投递 1已投递 2已确认 3投递失败',
    `retry_count` int NOT NULL DEFAULT '0' COMMENT '重试次数',
    `next_retry_time` datetime DEFAULT NULL COMMENT '下次重试时间',
    `error_msg` varchar(512) DEFAULT NULL COMMENT '最近一次失败原因',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_message_uuid` (`message_uuid`),
    KEY `idx_status_retry` (`status`,`next_retry_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='本地消息表(可靠投递)';
CREATE TABLE `tb_local_message_1` LIKE `tb_local_message_0`;

DROP TABLE IF EXISTS `tb_user_0`;
DROP TABLE IF EXISTS `tb_user_1`;
CREATE TABLE `tb_user_0` (
    `id` bigint unsigned NOT NULL COMMENT '主键',
    `phone` varchar(11) NOT NULL COMMENT '手机号',
    `nick_name` varchar(32) DEFAULT '' COMMENT '昵称',
    `icon` varchar(255) DEFAULT '' COMMENT '头像',
    `password` varchar(128) DEFAULT NULL COMMENT '预留密码字段',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE,
    UNIQUE KEY `uk_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户';
CREATE TABLE `tb_user_1` LIKE `tb_user_0`;

DROP TABLE IF EXISTS `tb_user_info_0`;
DROP TABLE IF EXISTS `tb_user_info_1`;
CREATE TABLE `tb_user_info_0` (
    `user_id` bigint unsigned NOT NULL COMMENT '主键，用户id',
    `city` varchar(64) DEFAULT '' COMMENT '常驻城市',
    `introduce` varchar(128) DEFAULT NULL COMMENT '个人介绍',
    `gender` tinyint unsigned DEFAULT '0' COMMENT '0未知 1男 2女',
    `birthday` date DEFAULT NULL COMMENT '生日',
    `credits` int unsigned DEFAULT '0' COMMENT '观演积分',
    `level` tinyint unsigned NOT NULL DEFAULT '1' COMMENT '会员等级，1-10，等级越高可参与优先购票',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='用户信息';
CREATE TABLE `tb_user_info_1` LIKE `tb_user_info_0`;

DROP TABLE IF EXISTS `tb_user_phone_0`;
DROP TABLE IF EXISTS `tb_user_phone_1`;
CREATE TABLE `tb_user_phone_0` (
    `phone` varchar(11) NOT NULL COMMENT '手机号',
    `user_id` bigint unsigned NOT NULL COMMENT '用户id',
    `create_time` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`phone`) USING BTREE,
    KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='手机号映射';
CREATE TABLE `tb_user_phone_1` LIKE `tb_user_phone_0`;

-- -------------------------------------------------------------
-- 初始化数据
-- -------------------------------------------------------------
INSERT INTO `tb_venue` (`id`, `name`, `city`, `address`, `longitude`, `latitude`, `venue_type`, `capacity`) VALUES
(20001,'杭州奥体中心体育馆','杭州','杭州市萧山区奔竞大道1111号',120.234,30.208,2,18000),
(20002,'上海梅赛德斯-奔驰文化中心','上海','上海市浦东新区世博大道1200号',121.489,31.185,2,18000),
(20003,'北京保利剧院','北京','北京市东城区东直门南大街14号',116.433,39.938,1,1500),
(20004,'成都城市音乐厅','成都','成都市青羊区一环路南一段24号',104.063,30.651,1,1200),
(20005,'广州长隆国际会展中心','广州','广州市番禺区大石街道汉溪大道',113.325,22.998,4,12000);

INSERT INTO `tb_show` (`id`, `name`, `venue_id`, `category`, `artist`, `poster`, `description`, `duration_minutes`, `min_price`, `status`) VALUES
(30001,'「星海」2026巡回演唱会·杭州站',20001,1,'林屿','/imgs/shows/star-sea.jpg','沉浸式舞台与交响乐队现场，一段关于潮汐与星光的旅程。',150,38000,1),
(30002,'话剧《雷雨》经典重排',20003,2,'北京人民艺术剧团','/imgs/shows/leiyu.jpg','曹禺经典之作，三一律结构下的家族悲剧。',135,18000,1),
(30003,'草莓音乐节·广州站',20005,3,'多组乐队','/imgs/shows/strawberry.jpg','两天两夜，三十组乐队轮番登台。',480,28000,1),
(30004,'CBA常规赛 广州龙狮 vs 辽宁本钢',20005,4,'广州龙狮','/imgs/shows/cba.jpg','2025-2026赛季常规赛焦点战。',120,12000,1),
(30005,'脱口秀大会·成都专场',20004,5,'笑果脱口秀','/imgs/shows/talkshow.jpg','一场没有脚本的夜晚，笑点密集。',100,15000,1),
(30006,'儿童剧《小王子》',20004,6,'木偶剧团','/imgs/shows/little-prince.jpg','以偶戏与音乐重新讲述小王子的旅行。',80,9000,1);

-- 场次 1002 / 1004 / 1006 / 1008 落在 showgo_0
-- 时间基于「当前日期」相对生成，保证任何时候拉起来都是有效的可售场次：
--   status=2 售票中：已开售、未开演     status=1 待开售：开售时间在未来
INSERT INTO `tb_show_session_1` (`id`, `show_id`, `venue_id`, `session_name`, `begin_time`, `end_time`, `sale_begin_time`, `sale_end_time`, `seat_total`, `seat_available`, `status`) VALUES
(1002,30001,20001,
 CONCAT(DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 5 DAY), '%m-%d'), ' 19:30'),
 DATE_ADD(CURDATE(), INTERVAL 5 DAY) + INTERVAL 19 HOUR + INTERVAL 30 MINUTE,
 DATE_ADD(CURDATE(), INTERVAL 5 DAY) + INTERVAL 22 HOUR,
 NOW() - INTERVAL 1 DAY,
 DATE_ADD(CURDATE(), INTERVAL 5 DAY) + INTERVAL 19 HOUR,
 688,688,2),
(1006,30003,20005,
 CONCAT(DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 15 DAY), '%m-%d'), ' 14:00'),
 DATE_ADD(CURDATE(), INTERVAL 15 DAY) + INTERVAL 14 HOUR,
 DATE_ADD(CURDATE(), INTERVAL 15 DAY) + INTERVAL 22 HOUR,
 NOW() + INTERVAL 2 DAY,
 DATE_ADD(CURDATE(), INTERVAL 15 DAY) + INTERVAL 13 HOUR + INTERVAL 30 MINUTE,
 688,688,1);
INSERT INTO `tb_show_session_0` (`id`, `show_id`, `venue_id`, `session_name`, `begin_time`, `end_time`, `sale_begin_time`, `sale_end_time`, `seat_total`, `seat_available`, `status`) VALUES
(1004,30002,20003,
 CONCAT(DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 10 DAY), '%m-%d'), ' 19:30'),
 DATE_ADD(CURDATE(), INTERVAL 10 DAY) + INTERVAL 19 HOUR + INTERVAL 30 MINUTE,
 DATE_ADD(CURDATE(), INTERVAL 10 DAY) + INTERVAL 21 HOUR + INTERVAL 45 MINUTE,
 NOW() + INTERVAL 2 DAY,
 DATE_ADD(CURDATE(), INTERVAL 10 DAY) + INTERVAL 19 HOUR,
 688,688,1),
(1008,30005,20004,
 CONCAT(DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 20 DAY), '%m-%d'), ' 20:00'),
 DATE_ADD(CURDATE(), INTERVAL 20 DAY) + INTERVAL 20 HOUR,
 DATE_ADD(CURDATE(), INTERVAL 20 DAY) + INTERVAL 21 HOUR + INTERVAL 40 MINUTE,
 NOW() + INTERVAL 2 DAY,
 DATE_ADD(CURDATE(), INTERVAL 20 DAY) + INTERVAL 19 HOUR + INTERVAL 30 MINUTE,
 688,688,1);

INSERT INTO `tb_seat_area` (`id`, `session_id`, `area_no`, `area_code`, `area_name`, `area_type`, `price`, `color`, `row_count`, `seat_per_row`) VALUES
(4000101,1002,1,'A','A区 内场VIP',2,128000,'#f56c6c',8,20),
(4000102,1002,2,'B','B区 前区',1,88000,'#e6a23c',12,24),
(4000103,1002,3,'C','C区 看台',1,38000,'#409eff',10,24),
(4000401,1004,1,'A','A区 池座',2,48000,'#f56c6c',8,20),
(4000402,1004,2,'B','B区 楼座',1,28000,'#e6a23c',12,24),
(4000403,1004,3,'C','C区 边座',1,18000,'#409eff',10,24),
(4000601,1006,1,'A','A区 舞台前',2,68000,'#f56c6c',8,20),
(4000602,1006,2,'B','B区 草坪',1,38000,'#e6a23c',12,24),
(4000603,1006,3,'C','C区 远眺',1,28000,'#409eff',10,24),
(4000801,1008,1,'A','A区 前排',2,38000,'#f56c6c',8,20),
(4000802,1008,2,'B','B区 中区',1,22000,'#e6a23c',12,24),
(4000803,1008,3,'C','C区 后排',1,15000,'#409eff',10,24);

-- tb_seat_area 是广播表，两个分片库的数据必须完全一致，因此这里补上落在 showgo_1 的场次票档
INSERT INTO `tb_seat_area` (`id`, `session_id`, `area_no`, `area_code`, `area_name`, `area_type`, `price`, `color`, `row_count`, `seat_per_row`) VALUES
(4000201,1001,1,'A','A区 内场VIP',2,128000,'#f56c6c',8,20),
(4000202,1001,2,'B','B区 前区',1,88000,'#e6a23c',12,24),
(4000203,1001,3,'C','C区 看台',1,38000,'#409eff',10,24),
(4000301,1003,1,'A','A区 内场VIP',2,128000,'#f56c6c',8,20),
(4000302,1003,2,'B','B区 前区',1,88000,'#e6a23c',12,24),
(4000303,1003,3,'C','C区 看台',1,38000,'#409eff',10,24),
(4000501,1005,1,'A','A区 池座',2,48000,'#f56c6c',8,20),
(4000502,1005,2,'B','B区 楼座',1,28000,'#e6a23c',12,24),
(4000503,1005,3,'C','C区 边座',1,18000,'#409eff',10,24),
(4000701,1007,1,'A','A区 场边',2,26000,'#f56c6c',8,20),
(4000702,1007,2,'B','B区 中区',1,18000,'#e6a23c',12,24),
(4000703,1007,3,'C','C区 上层看台',1,12000,'#409eff',10,24);

-- 用序号表批量生成座位
DROP TABLE IF EXISTS `tmp_seq`;
CREATE TABLE `tmp_seq` (`n` int NOT NULL, PRIMARY KEY (`n`));
INSERT INTO `tmp_seq` (`n`) VALUES (1),(2),(3),(4),(5),(6),(7),(8),(9),(10),(11),(12),(13),(14),(15),(16),(17),(18),(19),(20),
                                  (21),(22),(23),(24),(25),(26),(27),(28),(29),(30),(31),(32),(33),(34),(35),(36),(37),(38),(39),(40);

INSERT INTO `tb_show_seat_1`
(`id`,`session_id`,`area_id`,`area_code`,`row_no`,`col_no`,`seat_code`,`seat_type`,`price`,`status`)
SELECT a.session_id * 1000000 + a.area_no * 100000 + (r.n - 1) * 100 + c.n,
       a.session_id, a.id, a.area_code, r.n, c.n,
       CONCAT(a.area_code,'-',LPAD(r.n,2,'0'),'-',LPAD(c.n,2,'0')),
       a.area_type, a.price, 0
FROM `tb_seat_area` a
JOIN `tmp_seq` r ON r.n <= a.row_count
JOIN `tmp_seq` c ON c.n <= a.seat_per_row
WHERE a.session_id IN (1002,1006);

INSERT INTO `tb_show_seat_0`
(`id`,`session_id`,`area_id`,`area_code`,`row_no`,`col_no`,`seat_code`,`seat_type`,`price`,`status`)
SELECT a.session_id * 1000000 + a.area_no * 100000 + (r.n - 1) * 100 + c.n,
       a.session_id, a.id, a.area_code, r.n, c.n,
       CONCAT(a.area_code,'-',LPAD(r.n,2,'0'),'-',LPAD(c.n,2,'0')),
       a.area_type, a.price, 0
FROM `tb_seat_area` a
JOIN `tmp_seq` r ON r.n <= a.row_count
JOIN `tmp_seq` c ON c.n <= a.seat_per_row
WHERE a.session_id IN (1004,1008);

DROP TABLE IF EXISTS `tmp_seq`;

-- 用户 10002(_1) / 10004(_0) / 10006(_1)
INSERT INTO `tb_user_1` (`id`,`phone`,`nick_name`,`icon`) VALUES
(10002,'13800000002','观影人小柯','/imgs/icons/user-icon.png'),
(10006,'13800000006','剧场常客','/imgs/icons/user-icon.png');
INSERT INTO `tb_user_0` (`id`,`phone`,`nick_name`,`icon`) VALUES
(10004,'13800000004','音乐节玩家','/imgs/icons/user-icon.png');

INSERT INTO `tb_user_info_1` (`user_id`,`city`,`introduce`,`gender`,`credits`,`level`) VALUES
(10002,'杭州','一年看三十场演出',1,1280,3),
(10006,'深圳','只为听现场',2,860,2);
INSERT INTO `tb_user_info_0` (`user_id`,`city`,`introduce`,`gender`,`credits`,`level`) VALUES
(10004,'广州','乐队迷',1,2140,4);

INSERT INTO `tb_user_phone_1` (`phone`,`user_id`) VALUES ('13800000002',10002);
INSERT INTO `tb_user_phone_0` (`phone`,`user_id`) VALUES ('13800000004',10004);
INSERT INTO `tb_user_phone_1` (`phone`,`user_id`) VALUES ('13800000006',10006);

SET FOREIGN_KEY_CHECKS = 1;
