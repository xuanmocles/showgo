#!/usr/bin/env node
/**
 * 星幕票务 ShowGo —— 选座并发压测脚本
 *
 * 用来验证两件事（简历里"零超卖 / 限购生效"这两个数字就靠它产出）：
 *   1. same-seat     N 个并发请求抢同一个座位 -> 成功数必须恰好为 1
 *   2. distinct-seat N 个并发请求抢不同座位   -> 成功数应等于单用户限购数（默认 6）
 *
 * 用法：
 *   node tools/seat-concurrency-test.mjs <baseUrl> <sessionId> <concurrency> <token> <mode> [seatStart]
 *
 * 示例：
 *   TOKEN=$(curl -s -X POST "http://localhost:8080/api/user/login" \
 *     -H "Content-Type: application/json" -d '{"phone":"13800000001","code":"123456"}' | jq -r .data)
 *   node tools/seat-concurrency-test.mjs http://localhost:8080 1001 200 "$TOKEN" same-seat 42
 *
 * 注意：压测会占用座位，锁座有效期 15 分钟，到期由延迟队列自动释放；
 *      也可以登录后在"我的订单"里取消，立即释放。
 */

const [baseUrl, sessionIdArg, concurrencyArg, token, mode = 'same-seat', seatStartArg = '0'] = process.argv.slice(2);

if (!baseUrl || !sessionIdArg || !concurrencyArg || !token) {
  console.error('用法: node tools/seat-concurrency-test.mjs <baseUrl> <sessionId> <concurrency> <token> <same-seat|distinct-seat> [seatStart]');
  process.exit(1);
}

const sessionId = Number(sessionIdArg);
const concurrency = Number(concurrencyArg);
const seatStart = Number(seatStartArg);
const headers = { 'Content-Type': 'application/json', Authorization: token };

/** 发起一次锁座请求，返回 { ok, msg, ms } */
async function lockSeat(seatIndex) {
  const started = Date.now();
  try {
    const res = await fetch(`${baseUrl}/api/seat/lock`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ sessionId, seatIndexes: [seatIndex] })
    });
    const body = await res.json();
    return { ok: res.status === 200 && body.success === true, msg: body.errorMsg || '', ms: Date.now() - started };
  } catch (e) {
    return { ok: false, msg: String(e.message || e), ms: Date.now() - started };
  }
}

/** 读座位图，返回该座位状态与余票 */
async function inspectSeat(seatIndex) {
  const res = await fetch(`${baseUrl}/api/seat/map/${sessionId}`, { headers });
  const body = await res.json();
  const data = body.data;
  for (const area of data.areas) {
    const offset = seatIndex - area.startIndex;
    if (offset >= 0 && offset < area.rowCount * area.seatPerRow) {
      const row = Math.floor(offset / area.seatPerRow);
      const col = offset % area.seatPerRow;
      return { status: area.seats[row][col], seatAvailable: data.seatAvailable };
    }
  }
  return { status: null, seatAvailable: data.seatAvailable };
}

const percentile = (values, p) => {
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))];
};

async function main() {
  const seatIndexes = mode === 'same-seat'
    ? Array.from({ length: concurrency }, () => seatStart)
    : Array.from({ length: concurrency }, (_, i) => seatStart + i);

  console.log(`\n=== 选座并发测试 ===`);
  console.log(`目标=${baseUrl}  场次=${sessionId}  并发=${concurrency}  模式=${mode}  起始座位=${seatStart}`);

  const t0 = Date.now();
  const results = await Promise.all(seatIndexes.map(lockSeat));
  const wall = Date.now() - t0;

  const success = results.filter((r) => r.ok);
  const failure = results.filter((r) => !r.ok);
  const latencies = results.map((r) => r.ms);
  const reasonCount = failure.reduce((acc, r) => {
    const key = r.msg || 'unknown';
    acc[key] = (acc[key] || 0) + 1;
    return acc;
  }, {});

  console.log(`\n成功: ${success.length}    失败: ${failure.length}`);
  console.log(`耗时: ${wall} ms   吞吐: ${(concurrency / (wall / 1000)).toFixed(0)} 次/秒`);
  console.log(`延迟: P50=${percentile(latencies, 0.5)}ms  P95=${percentile(latencies, 0.95)}ms  P99=${percentile(latencies, 0.99)}ms  MAX=${Math.max(...latencies)}ms`);
  console.log(`失败原因分布: ${JSON.stringify(reasonCount, null, 0)}`);

  const verdict = mode === 'same-seat'
    ? (success.length === 1 ? '✅ 通过：并发抢同一座位只成功 1 次，零超卖' : `❌ 异常：同座位成功 ${success.length} 次`)
    : (success.length <= 6 ? `✅ 通过：成功 ${success.length} 次，未突破单用户限购 6 张` : `❌ 异常：成功 ${success.length} 次，突破了限购`);
  console.log(verdict);

  const seat = await inspectSeat(seatStart);
  console.log(`座位 ${seatStart} 状态=${seat.status}（1=锁定 2=已售）  场次余票=${seat.seatAvailable}`);
}

main().catch((e) => {
  console.error('压测执行失败:', e);
  process.exit(1);
});
