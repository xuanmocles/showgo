#!/usr/bin/env node
/**
 * 星幕票务 ShowGo —— 面试前冒烟测试
 *
 * 一条命令跑完：健康检查 → 登录 → 座位图 → 锁座 → 边界校验 → 下单 → 支付出票
 *              → 票夹 → 座位状态 → 退票回流，并输出 PASS/FAIL 表格。
 *
 * 用法（在项目根目录执行，需要 docker compose 已启动）：
 *   node tools/smoke-test.mjs
 *   node tools/smoke-test.mjs --session 1002 --phone 13800000002
 *
 * 可选环境变量：
 *   BASE_URL   默认 http://localhost:8080
 *   TEST_CODE  直接指定验证码（不填则自动从后端日志解析）
 */

import { execFileSync } from 'node:child_process';

const argv = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = argv.indexOf(`--${name}`);
  return i >= 0 && argv[i + 1] ? argv[i + 1] : fallback;
};

const BASE = process.env.BASE_URL || argOf('base', 'http://localhost:8080');
const BACKEND = process.env.BACKEND_URL || argOf('backend', 'http://localhost:8085');
const PHONE = process.env.TEST_PHONE || argOf('phone', '13800000001');
const SESSION = Number(argOf('session', 1001));
const OFF_SALE_SESSION = Number(argOf('off-sale-session', 1005));

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
let token = '';

function record(name, ok, detail = '') {
  results.push({ name, ok, detail });
  console.log(`${ok ? '✅ PASS' : '❌ FAIL'}  ${name}${detail ? `  —— ${detail}` : ''}`);
}

async function api(path, { method = 'GET', body, auth = true } = {}) {
  const headers = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (auth && token) headers.Authorization = token;
  const res = await fetch(BASE + path, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  let json;
  try {
    json = JSON.parse(text);
  } catch {
    json = { success: false, errorMsg: `非 JSON 响应(HTTP ${res.status}): ${text.slice(0, 120)}` };
  }
  return { httpStatus: res.status, ...json };
}

/** 从后端日志里解析最近一次验证码 */
function codeFromLogs() {
  const logs = execFileSync('docker', ['compose', 'logs', 'backend', '--tail', '300'], {
    encoding: 'utf8',
    stdio: ['ignore', 'pipe', 'ignore']
  });
  const normalized = logs.replace(/\s+/g, '');
  const matches = [...normalized.matchAll(/code=(\d{6})/g)];
  return matches.length ? matches[matches.length - 1][1] : null;
}

async function login() {
  const send = await api(`/api/user/code?phone=${PHONE}`, { method: 'POST', auth: false });
  record('发送登录验证码', send.success === true, send.errorMsg || `phone=${PHONE}`);
  await sleep(800);

  const code = process.env.TEST_CODE || codeFromLogs();
  if (!code) throw new Error('无法从后端日志解析验证码，请用 TEST_CODE=xxxxxx 指定');

  const loginRes = await api('/api/user/login', {
    method: 'POST',
    auth: false,
    body: { phone: PHONE, code }
  });
  record('验证码登录', loginRes.success === true && !!loginRes.data,
    loginRes.success ? `token=${String(loginRes.data).slice(0, 8)}…` : loginRes.errorMsg);
  if (!loginRes.success) throw new Error('登录失败，后续用例无法执行');
  token = loginRes.data;
}

async function seatMap(sessionId = SESSION) {
  const res = await api(`/api/seat/map/${sessionId}`);
  if (!res.success) throw new Error(`座位图读取失败: ${res.errorMsg}`);
  return res.data;
}

/** 在座位图中找 count 个同排连续空位 */
function findConsecutiveFree(map, count) {
  for (const area of map.areas) {
    for (let row = 0; row < area.rowCount; row++) {
      let run = 0;
      let start = -1;
      for (let col = 0; col < area.seatPerRow; col++) {
        if (area.seats[row][col] === 0) {
          if (run === 0) start = col;
          run++;
          if (run >= count) {
            const indexes = [];
            const codes = [];
            for (let i = 0; i < count; i++) {
              indexes.push(area.startIndex + row * area.seatPerRow + start + i);
              codes.push(
                `${area.areaCode}-${String(row + 1).padStart(2, '0')}-${String(start + i + 1).padStart(2, '0')}`
              );
            }
            return { area, row, indexes, codes };
          }
        } else {
          run = 0;
        }
      }
    }
  }
  return null;
}

function seatStatusOf(map, index) {
  for (const area of map.areas) {
    const offset = index - area.startIndex;
    if (offset >= 0 && offset < area.rowCount * area.seatPerRow) {
      return area.seats[Math.floor(offset / area.seatPerRow)][offset % area.seatPerRow];
    }
  }
  return null;
}

async function main() {
  console.log(`\n=== 星幕票务 ShowGo 冒烟测试 ===\n目标: ${BASE}  场次: ${SESSION}  手机号: ${PHONE}\n`);

  // ---------- 1. 健康检查 ----------
  // 注意：actuator 不走前端 nginx 的 /api 代理，直接用后端端口检查
  let health = {};
  try {
    health = await (await fetch(`${BACKEND}/actuator/health`)).json();
  } catch (e) {
    health = { status: `unreachable: ${e.message}` };
  }
  record('后端健康检查', health.status === 'UP', `status=${health.status}`);
  const shows = await api('/api/show/list?page=1&pageSize=3', { auth: false });
  record('演出列表（免登录）', shows.success === true && shows.data?.length > 0,
    `返回 ${shows.data?.length ?? 0} 条 / 共 ${shows.total} 条`);

  // ---------- 2. 登录 ----------
  await login();

  // ---------- 3. 座位图 ----------
  const map = await seatMap();
  record('读取座位图', map.areas?.length > 0,
    `分区 ${map.areas?.length} 个，座位 ${map.seatTotal}，余票 ${map.seatAvailable}`);

  // ---------- 4. 锁座成功 ----------
  const pick = findConsecutiveFree(map, 2);
  if (!pick) throw new Error('当前场次没有连续两个空位，请换场次或重置数据');
  const lock = await api('/api/seat/lock', {
    method: 'POST',
    body: { sessionId: SESSION, seatIndexes: pick.indexes }
  });
  record('锁座（2 个连座）', lock.success === true,
    lock.success ? `${pick.codes.join(',')}  金额=${lock.data.amount}分` : lock.errorMsg);
  if (!lock.success) throw new Error('锁座失败，后续用例无法执行');

  // ---------- 5. 重复锁座 ----------
  const lockAgain = await api('/api/seat/lock', {
    method: 'POST',
    body: { sessionId: SESSION, seatIndexes: pick.indexes }
  });
  record('重复锁同一座位被拒绝', lockAgain.success === false, lockAgain.errorMsg);

  // ---------- 6. 超过单笔限购 ----------
  const seven = findConsecutiveFree(await seatMap(), 7);
  if (seven) {
    const overLimit = await api('/api/seat/lock', {
      method: 'POST',
      body: { sessionId: SESSION, seatIndexes: seven.indexes }
    });
    record('单笔超过 6 张被拒绝', overLimit.success === false, overLimit.errorMsg);
  } else {
    record('单笔超过 6 张被拒绝', false, '没有找到 7 个连续空位，跳过');
  }

  // ---------- 7. 未开售场次 ----------
  const offMap = await api(`/api/seat/map/${OFF_SALE_SESSION}`);
  if (offMap.success && offMap.data?.areas?.length) {
    const offPick = findConsecutiveFree(offMap.data, 1);
    const offLock = await api('/api/seat/lock', {
      method: 'POST',
      body: { sessionId: OFF_SALE_SESSION, seatIndexes: [offPick.indexes[0]] }
    });
    record('未开售场次禁止锁座', offLock.success === false, offLock.errorMsg);
  }

  // ---------- 8. 无效访问令牌 ----------
  await sleep(2000);
  const badToken = await api('/api/ticket-order/create?accessToken=invalid-token', {
    method: 'POST',
    body: { sessionId: SESSION, lockToken: lock.data.lockToken }
  });
  record('无效访问令牌被拒绝', badToken.success === false, badToken.errorMsg);

  // ---------- 9. 下单 ----------
  await sleep(2000);
  const access = await api(`/api/ticket-order/access-token/${SESSION}`);
  record('领取一次性访问令牌', access.success === true && !!access.data);

  const order = await api(`/api/ticket-order/create?accessToken=${access.data}`, {
    method: 'POST',
    body: { sessionId: SESSION, lockToken: lock.data.lockToken }
  });
  record('下单（待支付）', order.success === true && order.data?.status === 1,
    order.success ? `订单号=${order.data.orderNo} 金额=${order.data.amount}分` : order.errorMsg);
  if (!order.success) throw new Error('下单失败，后续用例无法执行');

  // ---------- 10. 令牌复用 ----------
  await sleep(2000);
  const reuse = await api(`/api/ticket-order/create?accessToken=${access.data}`, {
    method: 'POST',
    body: { sessionId: SESSION, lockToken: lock.data.lockToken }
  });
  record('访问令牌不可复用', reuse.success === false, reuse.errorMsg);

  // ---------- 11. 支付出票 ----------
  const paid = await api('/api/ticket-order/pay', {
    method: 'POST',
    body: { orderId: order.data.id, payType: 1 }
  });
  record('支付并出票', paid.success === true && paid.data?.status === 3,
    paid.success ? `订单状态=${paid.data.status}` : paid.errorMsg);

  const tickets = await api('/api/ticket/my');
  const myTickets = (tickets.data || []).filter(
    (t) => pick.codes.includes(t.seatCode) && t.status === 1
  );
  record('票夹生成本次订单的 2 张有效票', tickets.success === true && myTickets.length === 2,
    myTickets.map((t) => `${t.seatCode}(状态${t.status})`).join(','));

  const mapAfterPay = await seatMap();
  const soldOk = pick.indexes.every((i) => seatStatusOf(mapAfterPay, i) === 2);
  record('座位状态变为已售(2)', soldOk, `余票=${mapAfterPay.seatAvailable}`);

  // ---------- 12. 退票回流 ----------
  const refund = await api('/api/ticket-order/refund', {
    method: 'POST',
    body: { orderId: order.data.id, reason: '冒烟测试' }
  });
  record('退票', refund.success === true && refund.data?.status === 5,
    refund.success ? `订单状态=${refund.data.status}` : refund.errorMsg);

  const mapAfterRefund = await seatMap();
  const backOk = pick.indexes.every((i) => seatStatusOf(mapAfterRefund, i) === 0);
  record('退票后座位回流为可售(0)', backOk, `余票=${mapAfterRefund.seatAvailable}`);

  // ---------- 13. 证据：座位图 Redis 内存 ----------
  try {
    const usage = execFileSync('docker', [
      'exec', 'showgo-redis', 'redis-cli', 'MEMORY', 'USAGE', `showgo-ticket:seat:map:{${SESSION}}`
    ], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim();
    record('座位图内存占用（可作为面试证据）', Number(usage) > 0,
      `${map.seatTotal} 个座位仅占 ${usage} 字节`);
  } catch {
    console.log('ℹ️  跳过 Redis 内存取证（docker 不可用）');
  }

  // ---------- 汇总 ----------
  const passed = results.filter((r) => r.ok).length;
  console.log(`\n=== 汇总：${passed}/${results.length} 通过 ===`);
  if (passed !== results.length) {
    console.log('\n未通过项：');
    results.filter((r) => !r.ok).forEach((r) => console.log(` - ${r.name}: ${r.detail}`));
    process.exitCode = 1;
  }
}

main().catch((e) => {
  console.error('\n执行中断:', e.message);
  const passed = results.filter((r) => r.ok).length;
  console.log(`已完成 ${passed}/${results.length}，详见上方输出`);
  process.exit(1);
});
