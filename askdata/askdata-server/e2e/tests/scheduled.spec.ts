import { test, expect } from '@playwright/test';
import { BASE, authHeaders, login } from './helpers';

test.describe('定时任务（A10）端到端', () => {
  test('创建 → 立即执行 → 历史 → 停用 → 删除', async ({ page, request }) => {
    const token = await login(page);
    const headers = authHeaders(token);

    // 先建一个 H2 数据源（指向应用库）
    const dsName = 'e2e-sched-' + Date.now();
    const configuration = JSON.stringify({
      host: '', port: 0, username: 'sa', password: '',
      database: 'jdbc:h2:file:./data/e2e;MODE=MySQL',
      extraJdbc: '', dbSchema: '', filename: '', sheets: [], mode: '',
      timeout: 10, lowVersion: false, ssl: false, poolSize: 5, engine: 'hive',
    });
    const dsCreated = await request.post(BASE + '/api/v1/datasource/add', {
      headers, data: { name: dsName, description: 'e2e', type: 'h2', configuration, tables: [] },
    });
    expect(dsCreated.status()).toBe(200);
    const dsBody = await dsCreated.json();
    const dsId = dsBody.data.id;
    expect(dsId).toBeTruthy();

    // 创建任务（每 60 秒）
    const taskName = 'e2e-task-' + Date.now();
    const saved = await request.post(BASE + '/api/v1/scheduled_task/save', {
      headers,
      data: {
        name: taskName,
        question: '统计用户数量',
        datasource_id: dsId,
        interval_seconds: 60,
        export_format: 'csv',
      },
    });
    expect(saved.status()).toBe(200);
    const savedBody = await saved.json();
    const taskId = savedBody.data.id;
    expect(taskId).toBeTruthy();

    // 列表里能查到
    const list = await request.post(BASE + '/api/v1/scheduled_task/list', { headers, data: {} });
    expect(list.status()).toBe(200);
    expect(JSON.stringify(await list.json())).toContain(taskName);

    // 立即执行：任务失败时落一条可读的失败历史（本环境可能已配置大模型，
    // 因此错误信息可能是「模型未输出可执行的 SQL」而非「未配置大模型」）
    const run = await request.post(BASE + '/api/v1/scheduled_task/run/' + taskId, { headers });
    expect(run.status()).toBe(200);
    const runBody = await run.json();
    expect(runBody.data.status).toBe('FAILED');
    expect(String(runBody.data.error || '')).toMatch(/大模型|chat model|模型|SQL/);

    const runs = await request.post(BASE + '/api/v1/scheduled_task/runs/' + taskId, { headers });
    expect(runs.status()).toBe(200);
    const runsBody = await runs.json();
    expect(Array.isArray(runsBody.data)).toBeTruthy();
    expect(runsBody.data.length).toBeGreaterThan(0);

    // 停用 → 删除
    const stopped = await request.post(BASE + '/api/v1/scheduled_task/stop/' + taskId, { headers });
    expect(stopped.status()).toBe(200);
    const removed = await request.post(BASE + '/api/v1/scheduled_task/delete/' + taskId, { headers });
    expect(removed.status()).toBe(200);

    // 清理数据源
    const dsRemoved = await request.post(
      BASE + '/api/v1/datasource/delete/' + dsId + '/' + encodeURIComponent(dsName), { headers });
    expect(dsRemoved.status()).toBe(200);
  });
});

