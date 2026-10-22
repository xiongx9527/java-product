import { test, expect } from '@playwright/test';
import { BASE, login, ADMIN_ACCOUNT, ADMIN_PASSWORD } from './helpers';

test.describe('登录与鉴权', () => {
  test('登录页正常渲染且无未捕获异常', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await page.goto(BASE + '/#/login');
    await expect(page.locator('.login-form input').first()).toBeVisible();
    await expect(page.locator('.login-form input[type=password]')).toBeVisible();
    await expect(page.locator('button.login-btn')).toBeVisible();
    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('错误口令被拒绝并提示', async ({ page }) => {
    await page.goto(BASE + '/#/login');
    await page.locator('.login-form input').first().fill(ADMIN_ACCOUNT);
    await page.locator('.login-form input[type=password]').fill('wrong-password-123');
    const [response] = await Promise.all([
      page.waitForResponse((r) => r.url().includes('/login/access-token')),
      page.locator('button.login-btn').click(),
    ]);
    expect(response.status()).toBeGreaterThanOrEqual(400);
    // 错误正文由后端 MessageResolver 给出，前端以 toast 展示
    await expect(page.locator('body')).toContainText(
      /账号或密码错误|Account or password error/,
      { timeout: 15000 },
    );
    expect(page.url()).toContain('/#/login');
  });

  test('admin 登录成功并进入问数页', async ({ page }) => {
    const token = await login(page);
    expect(token.length).toBeGreaterThan(10);
    await expect(page).toHaveURL(/\/#\/chat/);
    // 问数页渲染出会话区域（不要求具体文案，避免强绑定 UI 文案）
    await expect(page.locator('#app')).toBeVisible();
  });

  test('未登录访问受保护路由被重定向到登录页', async ({ page }) => {
    await page.goto(BASE + '/#/chat');
    await page.waitForURL(/\/#\/login/, { timeout: 20000 });
    expect(page.url()).toContain('/#/login');
  });
});

