import { test, expect } from '@playwright/test';
import { BASE, login } from './helpers';

const ROUTES: Array<{ path: string; name: string }> = [
  { path: '/#/chat', name: '问数' },
  { path: '/#/dashboard', name: '仪表盘' },
  { path: '/#/ds', name: '数据源' },
  { path: '/#/set/member', name: '成员管理' },
  { path: '/#/set/permission', name: '行/列权限' },
  { path: '/#/set/training', name: '训练' },
  { path: '/#/set/prompt', name: '自定义提示词' },
  { path: '/#/system/user', name: '用户' },
  { path: '/#/system/workspace', name: '工作空间' },
  { path: '/#/system/model', name: 'AI 模型' },
  { path: '/#/system/embedded', name: '嵌入应用' },
  { path: '/#/system/setting/parameter', name: '系统参数' },
  { path: '/#/system/setting/appearance', name: '外观' },
  { path: '/#/system/setting/authentication', name: '认证' },
  { path: '/#/system/audit', name: '审计' },
];

test.describe('主路由冒烟（登录后逐页渲染）', () => {
  test('所有主路由可渲染且无未捕获异常', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await login(page);

    const failed: string[] = [];
    for (const route of ROUTES) {
      errors.length = 0;
      await page.goto(BASE + route.path);
      await page.waitForLoadState('domcontentloaded');
      // 懒加载路由需要等真实内容渲染，固定 sleep 会误判为空页
      let text = '';
      try {
        await expect
          .poll(async () => ((await page.locator('#app').innerText().catch(() => '')) || '').trim().length, {
            timeout: 20000,
            intervals: [200, 400, 800],
          })
          .toBeGreaterThan(2);
        text = (await page.locator('#app').innerText()) || '';
      } catch (e) {
        text = '';
      }
      if (text.trim().length < 2) {
        failed.push(route.name + '(' + route.path + '): 页面内容为空');
      }
      if (errors.length > 0) {
        failed.push(route.name + '(' + route.path + '): ' + errors.join(' | '));
      }
      // 每页都应停留在目标路由，而不是被踢回登录页
      if (page.url().includes('/#/login')) {
        failed.push(route.name + '(' + route.path + '): 被重定向到登录页');
      }
    }
    expect(failed, failed.join('\n')).toEqual([]);
  });
});

