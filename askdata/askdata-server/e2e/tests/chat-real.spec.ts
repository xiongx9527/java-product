import { test, expect, Page } from '@playwright/test';
import { BASE, authHeaders, login } from './helpers';

/**
 * 真实环境前后端集成用例：登录 → 选择真实数据源 → 提问 → 后端生成 SQL/查询/出图 → 前端渲染。
 *
 * 前置：后端已配置默认对话模型（真实 LLM）与至少一个可用的数据库数据源。
 * 环境变量：
 *   E2E_DS_ID    指定数据源 id（优先）；
 *   E2E_DS_NAME  数据源名称（默认 e2e-mysql-askdata_verify）；
 *   E2E_QUESTION 提问文本。
 */
const DS_NAME = process.env.E2E_DS_NAME || 'e2e-mysql-askdata_verify';
const QUESTION = process.env.E2E_QUESTION || '各订单状态的订单数量和总金额分别是多少？按订单状态展示';

async function resolveDsId(page: Page, token: string): Promise<number> {
  if (process.env.E2E_DS_ID) {
    return Number(process.env.E2E_DS_ID);
  }
  const res = await page.request.get(BASE + '/api/v1/datasource/list', {
    headers: authHeaders(token),
  });
  const body = await res.json();
  const list: any[] = body.data || [];
  const found = list.find((d) => d.name === DS_NAME);
  if (!found) {
    throw new Error(`datasource ${DS_NAME} not found; have: ${list.map((d) => d.name).join(',')}`);
  }
  return found.id;
}

/** 解析 SSE 正文中的 data: 帧。 */
function parseFrames(sse: string): any[] {
  return sse
    .split('\n')
    .filter((line) => line.trim().startsWith('data:'))
    .map((line) => {
      try {
        return JSON.parse(line.trim().slice(5).trim());
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

test.describe('真实环境问数闭环', () => {
  test('选择真实数据源并完成一次问数（SQL + 数据 + 图表渲染）', async ({ page }) => {
    test.setTimeout(240000);
    const consoleErrors: string[] = [];
    page.on('pageerror', (e) => consoleErrors.push(String(e)));

    const token = await login(page);
    const dsId = await resolveDsId(page, token);

    await page.goto(`${BASE}/#/chat/index?start_chat=${dsId}`);
    // 同一路由仅 query 变化时 Vue 组件会复用、onMounted 不再触发 start_chat 建会话，
    // 因此重新加载一次让页面以 start_chat 参数冷启动（等价于外部链接打开）。
    await page.reload();
    // el-input 的 class 落在外层 div，真正的输入框是内部 textarea
    const input = page.locator('.input-area textarea');
    await expect(input).toBeVisible({ timeout: 30000 });

    await input.fill(QUESTION);
    const [questionResponse] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/v1/chat/question') && r.request().method() === 'POST',
        { timeout: 180000 },
      ),
      page.locator('button.input-icon').click(),
    ]);
    expect(questionResponse.status()).toBe(200);

    // ① 后端链路：真实 LLM 生成 SQL → 执行成功 → 图表配置 → 服务端出图
    const frames = parseFrames(await questionResponse.text());
    const types = new Set(frames.map((f) => f.type));
    expect(Array.from(types)).toEqual(
      expect.arrayContaining(['id', 'sql', 'sql-data', 'chart', 'imageUrl', 'finish']),
    );
    expect(types.has('error'), 'SSE 不应包含 error 帧').toBe(false);
    const sql = frames.find((f) => f.type === 'sql')?.content || '';
    expect(sql).toMatch(/FROM `orders`|FROM orders/i);
    expect(sql.toLowerCase()).toContain('group by');
    expect(frames.find((f) => f.type === 'sql-data')?.content).toBe('execute-success');
    const imageUrl = frames.find((f) => f.type === 'imageUrl')?.content;
    expect(imageUrl).toMatch(/^\/api\/chart\/image\//);

    // ② 落库数据：真实 MySQL 结果（订单状态枚举 + 已完成金额合计 3598）
    const recordId = frames.find((f) => f.type === 'id')?.id;
    expect(recordId).toBeTruthy();
    const dataRes = await page.request.get(`${BASE}/api/v1/chat/record/${recordId}/data`, {
      headers: authHeaders(token),
    });
    expect(dataRes.status()).toBe(200);
    const rows: any[] = (await dataRes.json()).data.data;
    // 列别名由模型决定（status/order_status、cnt/total/total_amount...），因此按值断言：
    // 三个订单状态齐全，且「已完成」订单金额合计 3598。
    const stringValues = rows.flatMap((r) => Object.values(r).filter((v) => typeof v === 'string'));
    const numberValues = rows.flatMap((r) => Object.values(r).map((v) => Number(v)).filter(Number.isFinite));
    expect(stringValues, JSON.stringify(rows)).toEqual(
      expect.arrayContaining(['已完成', '已取消', '待发货']),
    );
    expect(numberValues, JSON.stringify(rows)).toContain(3598);

    // ③ 前端渲染：会话记录 + 图表容器/Canvas + 服务端图表图片可访问
    // 标题由模型生成（不固定），以 SSE chart 帧里的 title 为基准，避免非确定性断言。
    const chartConfig = JSON.parse(frames.find((f) => f.type === 'chart').content || '{}');
    const chartTitle: string = chartConfig.title || '';
    expect(chartTitle, JSON.stringify(chartConfig)).not.toBe('');
    await expect(page.locator('.chat-record-list')).toContainText(chartTitle, { timeout: 120000 });
    const chart = page.locator('.chart-component-container');
    await expect(chart.first()).toBeVisible({ timeout: 120000 });
    await expect(chart.locator('.title').first()).toContainText(chartTitle);
    // 图表类型由模型推荐（column/bar/line/pie/table），table 走表格渲染，
    // 因此断言「canvas 或表格内容」二者其一可见，避免非确定性用例。
    const canvas = chart.locator('canvas').first();
    const tableLike = chart.locator('.chart-block table, .chart-block [class*=table]').first();
    await expect(canvas.or(tableLike)).toBeVisible({ timeout: 120000 });
    const imageRes = await page.request.get(BASE + imageUrl, { headers: authHeaders(token) });
    expect(imageRes.status()).toBe(200);
    expect(imageRes.headers()['content-type']).toContain('image/png');

    await page.screenshot({ path: '/tmp/askdata-e2e/chat-ui.png', fullPage: true });
    expect(consoleErrors, consoleErrors.join('\n')).toEqual([]);
  });
});
