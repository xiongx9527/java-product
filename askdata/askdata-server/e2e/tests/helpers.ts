import { Page } from '@playwright/test';

export const BASE = 'http://127.0.0.1:8000';
export const ADMIN_ACCOUNT = 'admin';
export const ADMIN_PASSWORD = 'SQLBot@123456';

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

