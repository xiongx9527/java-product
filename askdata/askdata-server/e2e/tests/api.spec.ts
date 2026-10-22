import { test, expect } from '@playwright/test';
import { BASE, authHeaders, login } from './helpers';

test.describe('接口层（登录后带 token 调用）', () => {
  test('登录健康检查可用', async ({ request }) => {
    const response = await request.get(BASE + '/api/v1/login/health');
    expect(response.ok()).toBeTruthy();
    const body = await response.json();
    expect(JSON.stringify(body)).toContain('askdata login up');
  });

  test('用户信息 / 数据源列表 / 会话列表可用', async ({ page, request }) => {
    const token = await login(page);
    const headers = authHeaders(token);

    const info = await request.get(BASE + '/api/v1/user/info', { headers });
    expect(info.status()).toBe(200);
    const infoBody = await info.json();
    expect(JSON.stringify(infoBody)).toContain('admin');

    const ds = await request.get(BASE + '/api/v1/datasource/list', { headers });
    expect(ds.status()).toBe(200);

    const chats = await request.get(BASE + '/api/v1/chat/list', { headers });
    expect(chats.status()).toBe(200);
  });

  test('无 token 访问受保护接口返回未授权', async ({ request }) => {
    const response = await request.get(BASE + '/api/v1/datasource/list');
    expect([401, 403]).toContain(response.status());
  });

  test('数据源增删闭环（H2 指向应用库）', async ({ page, request }) => {
    const token = await login(page);
    const headers = authHeaders(token);
    const name = 'e2e-h2-' + Date.now();
    const configuration = JSON.stringify({
      host: '',
      port: 0,
      username: 'sa',
      password: '',
      database: 'jdbc:h2:file:./data/e2e;MODE=MySQL',
      extraJdbc: '',
      dbSchema: '',
      filename: '',
      sheets: [],
      mode: '',
      timeout: 10,
      lowVersion: false,
      ssl: false,
      poolSize: 5,
      engine: 'hive',
    });

    const created = await request.post(BASE + '/api/v1/datasource/add', {
      headers,
      data: { name, description: 'e2e', type: 'h2', configuration, tables: [] },
    });
    expect(created.status()).toBe(200);
    const createdBody = await created.json();
    const id = createdBody && createdBody.data ? createdBody.data.id : undefined;
    expect(id, JSON.stringify(createdBody)).toBeTruthy();

    const list = await request.get(BASE + '/api/v1/datasource/list', { headers });
    expect(JSON.stringify(await list.json())).toContain(name);

    const removed = await request.post(BASE + '/api/v1/datasource/delete/' + id + '/' + encodeURIComponent(name), {
      headers,
    });
    expect(removed.status()).toBe(200);
    const after = await request.get(BASE + '/api/v1/datasource/list', { headers });
    expect(JSON.stringify(await after.json())).not.toContain(name);
  });
});

