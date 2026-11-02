import { Page } from '@playwright/test';

export const BASE = 'http://127.0.0.1:8000';
export const ADMIN_ACCOUNT = 'admin';
export const ADMIN_PASSWORD = 'SQLBot@123456';

/**
 * e2e 数据源用例指向的本地应用库（MySQL 8）。
 * 默认值匹配本机联调环境，可通过 ASKDATA_DB_* 环境变量覆盖；不含生产凭据。
 */
export const DEV_MYSQL = {
  host: process.env.ASKDATA_DB_HOST || '127.0.0.1',
  port: Number(process.env.ASKDATA_DB_PORT || 3307),
  username: process.env.ASKDATA_DB_USER || 'root',
  password: process.env.ASKDATA_DB_PASSWORD || '',
  database: process.env.ASKDATA_DB_NAME || 'askdata',
};

/** 生成数据源 configuration（与 DatasourceConf 字段一致）。 */
export function mysqlConf() {
  return JSON.stringify({
    host: DEV_MYSQL.host,
    port: DEV_MYSQL.port,
    username: DEV_MYSQL.username,
    password: DEV_MYSQL.password,
    database: DEV_MYSQL.database,
    extraJdbc: '',
    dbSchema: '',
    filename: '',
    sheets: [],
    mode: '',
    timeout: 10,
    lowVersion: false,
    ssl: false,
    poolSize: 5,
  });
}

/** 通过 UI 登录并返回 access_token（同时让页面进入已登录状态）。 */
export async function login(page: Page): Promise<string> {
  await page.goto(BASE + '/#/login');
  const username = page.locator('.login-form input').first();
  await username.waitFor({ state: 'visible', timeout: 20000 });
  await username.fill(ADMIN_ACCOUNT);
  await page.locator('.login-form input[type=password]').fill(ADMIN_PASSWORD);
  const [response] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/login/access-token'), { timeout: 20000 }),
    page.locator('button.login-btn').click(),
  ]);
  const body = await response.json();
  await page.waitForURL(/\/#\/chat/, { timeout: 20000 });
  const token = body && body.data ? body.data.access_token : undefined;
  if (!token) {
    throw new Error('login response has no access_token: ' + JSON.stringify(body));
  }
  return token as string;
}

export function authHeaders(token: string): Record<string, string> {
  return { 'X-SQLBOT-TOKEN': 'Bearer ' + token };
}
