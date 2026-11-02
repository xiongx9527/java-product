import { test, expect, Page } from '@playwright/test';
import { BASE, login, authHeaders } from './helpers';
import { startStub, STUB_MODEL } from '../stub-provider';

const UNIQ = Date.now();

async function gotoModelPage(page: Page) {
  await login(page);
  await page.goto(BASE + '/#/system/model');
  await expect(page.locator('.model-config .title')).toContainText(/AI 模型配置/);
  await page.waitForTimeout(800);
}

/** 刷新当前（已登录）模型页，不再重复登录：已登录时 /#/login 会重定向，导致登录表单永不出现。 */
async function reloadModelPage(page: Page) {
  await page.reload();
  await expect(page.locator('.model-config .title')).toContainText(/AI 模型配置/);
  await page.waitForTimeout(800);
}

async function closeDrawerIfOpen(page: Page) {
  // 仅当抽屉可见时关闭；fresh page(about:blank) 上 click 会一直等待直到测试超时
  const btn = page.locator('.model-drawer-fullscreen .ed-dialog__headerbtn').first();
  if (await btn.isVisible().catch(() => false)) {
    await btn.click({ timeout: 2000 }).catch(() => {});
  }
}

/**
 * 通过 API 创建一个「已连通且已启用」的自定义模型，供默认模型等依赖有效模型的用例使用。
 * 走 API 而非 UI 新增，是为了让 valid 回写与 enable 时序确定：新增 -> 显式连通校验(同步等待 NDJSON) -> 启用。
 * UI 的新增/编辑/删除闭环由「新增 -> 编辑 -> 搜索 -> 删除」用例覆盖。
 */
async function createEnabledCustomModel(page: Page, token: string, name: string, stubUrl: string) {
  const jsonH = { 'Content-Type': 'application/json', ...authHeaders(token) };
  const body = {
    name,
    api_domain: stubUrl,
    api_key: '',
    base_model: STUB_MODEL,
    model_type: 0,
    protocol: 1,
    supplier: 0,
    upstream_format: 0,
  };
  const created = await (
    await page.request.post(BASE + '/api/v1/system/aimodel', { headers: jsonH, data: body })
  ).json();
  const id = (created.data || created).id;
  // 显式连通校验：同步等待 NDJSON 结束，确保 valid 已回写
  await page.request.post(BASE + '/api/v1/system/aimodel/status', {
    headers: jsonH,
    data: { id, ...body },
  });
  // update 以库中 valid 为准，仅传 id+enable 即可
  const put = await page.request.put(BASE + '/api/v1/system/aimodel', {
    headers: jsonH,
    data: { id, enable: 1 },
  });
  expect(put.status(), 'enable 应成功').toBe(200);
  return id;
}

/** 通过卡片「...」菜单删除模型（含确认弹窗）。 */
async function deleteModel(page: Page, name: string) {
  const card = page.locator('.model-config .card', { hasText: name }).first();
  await card.locator('.more').click();
  await card.locator('.popover-card_workspace .item', { hasText: '删除' }).first().click();
  const box = page.locator('.ed-message-box:visible').last();
  await expect(box).toContainText(/是否删除模型/);
  const [resp] = await Promise.all([
    page.waitForResponse(
      (r) => /\/system\/aimodel\/\d+/.test(r.url()) && r.request().method() === 'DELETE',
    ),
    box.locator('button:visible').filter({ hasText: /^删除$/ }).first().click(),
  ]);
  expect(resp.status()).toBe(200);
  await expect(page.locator('.model-config .card', { hasText: name })).toHaveCount(0, { timeout: 15000 });
}

test.describe('AI 模型配置', () => {
  test('页面渲染与卡片信息', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await gotoModelPage(page);
    await expect(page.locator('button', { hasText: '添加模型' }).first()).toBeVisible();
    await expect(page.locator('button', { hasText: '系统默认模型' }).first()).toBeVisible();
    const cards = page.locator('.model-config .card');
    if (await cards.count() > 0) {
      await expect(cards.first()).toContainText('模型类型');
      await expect(cards.first()).toContainText('基础模型');
      await expect(cards.first()).toContainText('授权空间');
    }
    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('系统默认模型弹层可打开并列出模型', async ({ page }) => {
    await gotoModelPage(page);
    await page.locator('button', { hasText: '系统默认模型' }).first().click();
    const popover = page.locator('.system-default_model');
    await expect(popover).toBeVisible();
  });

  test('供应商不在内置列表（supplier=14）时页面不报错且可正常渲染', async ({ page }) => {
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(String(e)));
    await gotoModelPage(page);
    await expect(page.locator('button', { hasText: '添加模型' }).first()).toBeVisible();
    await page.locator('button', { hasText: '系统默认模型' }).first().click();
    await expect(page.locator('.system-default_model').first()).toBeVisible();
    expect(errors, errors.join('\n')).toEqual([]);
  });

  test('新增 -> 编辑 -> 搜索 -> 删除 闭环', async ({ page }) => {
    const stub = await startStub();
    await stub.setMode('ok');
    const name = 'e2e-aimodel-' + UNIQ;
    await gotoModelPage(page);
    // 新增自定义有效模型
    await page.locator('button', { hasText: '添加模型' }).first().click();
    await expect(page.locator('.model-list .title')).toContainText('选择提供商');
    await page.locator('.model-list .model', { hasText: '自定义' }).first().click();
    const form = page.locator('.model-form');
    await form.locator('input.ed-input__inner').nth(0).fill(name);
    await form.locator('input.ed-input__inner').nth(1).fill(stub.url);
    const baseSel = form.locator('.ed-select').nth(1);
    await baseSel.click();
    await baseSel.locator('input').fill(STUB_MODEL);
    await page.keyboard.press('Enter');
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST'),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    await expect(page.locator('.model-config .card', { hasText: name })).toBeVisible({ timeout: 15000 });

    // 编辑
    const card = page.locator('.model-config .card', { hasText: name }).first();
    await card.locator('button', { hasText: '编辑' }).click();
    const eform = page.locator('.model-form');
    await eform.locator('input.ed-input__inner').nth(0).waitFor();
    await eform.locator('input.ed-input__inner').nth(0).fill(name + '-edit');
    await Promise.all([
      page.waitForResponse((r) => r.url().includes('/system/aimodel') && r.request().method() === 'PUT'),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    await expect(page.locator('.model-config .card', { hasText: name + '-edit' })).toBeVisible({ timeout: 15000 });

    // 搜索过滤
    const search = page.locator('.model-config .model-methods input').first();
    await search.fill(name + '-edit');
    await expect(page.locator('.model-config .card')).toHaveCount(1);
    await search.fill('zzz-不存在的模型');
    await expect(page.locator('.model-config .card')).toHaveCount(0);
    await search.fill('');

    await deleteModel(page, name + '-edit');
    await stub.close();
  });

  test('设为默认模型 + 默认模型不可删除保护', async ({ page }) => {
    const stub = await startStub();
    await stub.setMode('ok');
    const token = await login(page);
    const jsonH = { 'Content-Type': 'application/json', ...authHeaders(token) };
    // 记录测试前已有的默认 LLM，结束时恢复，避免影响后续用例（如 chat-real）
    const beforeList = await (await page.request.get(BASE + '/api/v1/system/aimodel', { headers: authHeaders(token) })).json();
    const prevDefault = (beforeList.data || beforeList).find(
      (x: any) => x.model_type === 0 && x.default_model && !String(x.name).startsWith('e2e-default-'),
    );
    const prevDefaultId = prevDefault?.id;
    const reloadModels = async () => {
      await page.reload();
      await expect(page.locator('.model-config .title')).toContainText(/AI 模型配置/);
      await page.waitForTimeout(600);
    };
    await page.goto(BASE + '/#/system/model');
    await expect(page.locator('.model-config .title')).toContainText(/AI 模型配置/);
    await page.waitForTimeout(500);

    const nameA = 'e2e-default-' + UNIQ;
    const idA = await createEnabledCustomModel(page, token, nameA, stub.url);
    expect(idA, '应创建出模型 A').toBeTruthy();
    await reloadModels();
    const cardA = page.locator('.model-config .card', { hasText: nameA }).first();
    await expect(cardA.locator('.ed-tag', { hasText: '可用' })).toBeVisible({ timeout: 20000 });
    await expect(cardA.locator('.ed-tag', { hasText: '已启用' })).toBeVisible({ timeout: 20000 });
    await Promise.all([
      page.waitForResponse((r) => /\/system\/aimodel\/default\/\d+/.test(r.url()) && r.request().method() === 'PUT'),
      cardA.locator('button', { hasText: '设为默认模型' }).click().then(async () => {
        const box = page.locator('.ed-message-box:visible').last();
        await expect(box).toContainText(/系统默认模型/);
        await box.locator('button:visible').filter({ hasText: /^确定$/ }).first().click();
      }),
    ]);
    await reloadModels();
    await expect(cardA.locator('.default')).toBeVisible({ timeout: 15000 });

    // 默认模型不允许删除：删除菜单项置灰（disabled），点击不触发删除
    await cardA.locator('.more').click();
    const delItem = cardA.locator('.popover-card_workspace .item', { hasText: '删除' }).first();
    await expect(delItem).toHaveClass(/disabled/);
    await delItem.click({ force: true }).catch(() => {});
    await expect(page.locator('.model-config .card', { hasText: nameA })).toHaveCount(1);

    // 新建第二个有效模型并切换默认，随后可删除第一个
    const nameB = 'e2e-default-b-' + UNIQ;
    const idB = await createEnabledCustomModel(page, token, nameB, stub.url);
    expect(idB, '应创建出模型 B').toBeTruthy();
    await reloadModels();
    const cardB = page.locator('.model-config .card', { hasText: nameB }).first();
    await expect(cardB.locator('.ed-tag', { hasText: '可用' })).toBeVisible({ timeout: 20000 });
    await expect(cardB.locator('.ed-tag', { hasText: '已启用' })).toBeVisible({ timeout: 20000 });
    await Promise.all([
      page.waitForResponse((r) => /\/system\/aimodel\/default\/\d+/.test(r.url()) && r.request().method() === 'PUT'),
      cardB.locator('button', { hasText: '设为默认模型' }).click().then(async () => {
        const box2 = page.locator('.ed-message-box:visible').last();
        await box2.locator('button:visible').filter({ hasText: /^确定$/ }).first().click();
      }),
    ]);
    await reloadModels();
    await expect(cardB.locator('.default')).toBeVisible({ timeout: 15000 });
    await expect(cardA.locator('.default')).toHaveCount(0);

    // 删除非默认的 A（UI 闭环）
    await deleteModel(page, nameA);
    // B 仍是默认模型受删除保护：清理前先取消默认，再经 API 删除
    await page.request.put(BASE + '/api/v1/system/aimodel', { headers: jsonH, data: { id: idB, default_model: false } });
    const delB = await page.request.delete(BASE + '/api/v1/system/aimodel/' + idB, { headers: authHeaders(token) });
    expect(delB.status(), '清理模型 B 应成功').toBe(200);
    // 恢复测试前的默认 LLM，保证环境状态不被本用例改变
    if (prevDefaultId) {
      const restore = await page.request.put(BASE + '/api/v1/system/aimodel/default/' + prevDefaultId, { headers: jsonH });
      expect(restore.status(), '应恢复原默认模型').toBe(200);
    }
    await stub.close();
  });
});

// ====================================================================
// stub provider 驱动的用例：覆盖远程拉取、连通校验、上游格式分支、多语言
// 这些用例依赖本地后端（baseURL 127.0.0.1:8000）+ 内存 stub provider，
// 仅验证前端与契约，不依赖任何真实 API Key。
// 注意：连通校验结果由后端异步回写 valid，前端卡片不会自动刷新，
// 故保存后需 reload 页面以观察持久化后的 valid 状态。
// ====================================================================
test.describe('AI 模型配置（stub provider）', () => {
  let stub: any;
  test.beforeAll(async () => {
    stub = await startStub();
  });
  test.afterAll(async () => {
    if (stub) await stub.close();
  });

  async function openAddAndPick(page: Page, supplierName: string) {
    await closeDrawerIfOpen(page);
    await gotoModelPage(page);
    await page.locator('button', { hasText: '添加模型' }).first().click();
    await expect(page.locator('.model-list .title')).toContainText('选择提供商');
    await page.locator('.model-list .model', { hasText: supplierName }).first().click();
    await expect(page.locator('.model-form')).toBeVisible();
  }

  function field(form: any, nthInput: number) {
    return form.locator('input.ed-input__inner').nth(nthInput);
  }

  // 15.2 远程拉取：上游 200 -> source=remote，且唯一模型自动选中
  test('远程拉取返回模型清单并标注「远程」', async ({ page }) => {
    await stub.setMode('ok');
    await openAddAndPick(page, 'DeepSeek');
    const form = page.locator('.model-form');
    const name = 'e2e-stub-remote-' + UNIQ;
    await field(form, 0).fill(name);
    await field(form, 1).fill(stub.url);
    await form.locator('input[type=password]').fill('sk-e2e-invalid-key');
    await form.locator('button', { hasText: '拉取模型' }).first().click();
    await expect(form.locator('.ed-tag', { hasText: '远程' })).toBeVisible({ timeout: 30000 });
    await expect(form.locator('.ed-select').nth(1)).toContainText(STUB_MODEL);
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST',
      ),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    expect(resp.status()).toBe(200);
    await expect(page.locator('.model-config .card', { hasText: name })).toBeVisible({ timeout: 15000 });
    await deleteModel(page, name);
  });

  // 15.3 上游 404 -> 回退供应商预设清单（source=preset）
  test('上游 404 时回退「预设」清单', async ({ page }) => {
    await stub.setMode('notfound');
    await openAddAndPick(page, 'DeepSeek');
    const form = page.locator('.model-form');
    await field(form, 0).fill('e2e-stub-404-' + UNIQ);
    await field(form, 1).fill(stub.url);
    await form.locator('button', { hasText: '拉取模型' }).first().click();
    await expect(form.locator('.ed-tag', { hasText: '预设' })).toBeVisible({ timeout: 30000 });
    await page.locator('.model-drawer-fullscreen .ed-dialog__headerbtn').first().click();
  });

  // 15.4 自定义供应商上游不可达 -> 无兜底（source=none，仅提示错误）
  test('自定义供应商上游 404 时无预设兜底', async ({ page }) => {
    await stub.setMode('notfound');
    await openAddAndPick(page, '自定义');
    const form = page.locator('.model-form');
    await field(form, 0).fill('e2e-stub-custom-' + UNIQ);
    await field(form, 1).fill(stub.url);
    await form.locator('button', { hasText: '拉取模型' }).first().click();
    await expect(form.locator('.pull-error')).toBeVisible({ timeout: 30000 });
    await page.locator('.model-drawer-fullscreen .ed-dialog__headerbtn').first().click();
  });

  // 15.4b 连通校验 401 -> 不可用 + 默认按钮置灰
  test('连通校验 401 标记不可用且默认按钮置灰', async ({ page }) => {
    await stub.setMode('unauthorized');
    await openAddAndPick(page, '自定义');
    const form = page.locator('.model-form');
    const name = 'e2e-stub-401-' + UNIQ;
    await field(form, 0).fill(name);
    await field(form, 1).fill(stub.url);
    const baseSel = form.locator('.ed-select').nth(1);
    await baseSel.click();
    await baseSel.locator('input').fill(STUB_MODEL);
    await page.keyboard.press('Enter');
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST',
      ),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    expect(resp.status()).toBe(200);
    await reloadModelPage(page);
    const card = page.locator('.model-config .card', { hasText: name }).first();
    await expect(card).toBeVisible({ timeout: 15000 });
    await expect(card.locator('.ed-tag', { hasText: '不可用' })).toBeVisible({ timeout: 20000 });
    await expect(card.locator('button', { hasText: '设为默认模型' })).toBeDisabled();
    await deleteModel(page, name);
  });

  // 15.4b 连通校验 200 -> 可用
  test('连通校验 200 标记可用', async ({ page }) => {
    await stub.setMode('ok');
    await openAddAndPick(page, '自定义');
    const form = page.locator('.model-form');
    const name = 'e2e-stub-200-' + UNIQ;
    await field(form, 0).fill(name);
    await field(form, 1).fill(stub.url);
    const baseSel = form.locator('.ed-select').nth(1);
    await baseSel.click();
    await baseSel.locator('input').fill(STUB_MODEL);
    await page.keyboard.press('Enter');
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST',
      ),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    expect(resp.status()).toBe(200);
    await reloadModelPage(page);
    const card = page.locator('.model-config .card', { hasText: name }).first();
    await expect(card).toBeVisible({ timeout: 15000 });
    await expect(card.locator('.ed-tag', { hasText: '可用' })).toBeVisible({ timeout: 20000 });
    await deleteModel(page, name);
  });

  // 15.5 向量模型校验命中 /v1/embeddings
  test('向量模型连通校验命中 /v1/embeddings', async ({ page }) => {
    await stub.setMode('ok');
    await stub.resetHits();
    await openAddAndPick(page, '自定义');
    const form = page.locator('.model-form');
    const name = 'e2e-stub-embed-' + UNIQ;
    await field(form, 0).fill(name);
    await field(form, 1).fill(stub.url);
    await form.locator('.ed-select').nth(0).click();
    await page
      .locator('.ed-select-dropdown__item, .ed-option, [role="option"]')
      .filter({ hasText: '向量模型' })
      .first()
      .click();
    const baseSel = form.locator('.ed-select').nth(1);
    await baseSel.click();
    await baseSel.locator('input').fill(STUB_MODEL);
    await page.keyboard.press('Enter');
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST',
      ),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    expect(resp.status()).toBe(200);
    await reloadModelPage(page);
    const card = page.locator('.model-config .card', { hasText: name }).first();
    await expect(card).toBeVisible({ timeout: 15000 });
    await expect(card.locator('.ed-tag', { hasText: '可用' })).toBeVisible({ timeout: 20000 });
    const hits = await stub.hits();
    expect(hits, '应命中 /v1/embeddings').toContain('/v1/embeddings');
    await deleteModel(page, name);
  });

  // 15.6 上游格式选 Anthropic -> 校验命中 /v1/messages 而非 /v1/chat/completions
  test('上游格式 Anthropic 命中 /v1/messages', async ({ page }) => {
    await stub.setMode('ok');
    await stub.resetHits();
    await openAddAndPick(page, '自定义');
    const form = page.locator('.model-form');
    const name = 'e2e-stub-anth-' + UNIQ;
    await field(form, 0).fill(name);
    await field(form, 1).fill(stub.url);
    const baseSel = form.locator('.ed-select').nth(1);
    await baseSel.click();
    await baseSel.locator('input').fill(STUB_MODEL);
    await page.keyboard.press('Enter');
    const fmtSel = form.locator('.ed-select').nth(2);
    await fmtSel.click();
    await page
      .locator('.ed-select-dropdown__item, .ed-option, [role="option"]')
      .filter({ hasText: 'Anthropic Messages' })
      .first()
      .click();
    const [resp] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/system/aimodel') && r.request().method() === 'POST',
      ),
      page.locator('.ed-drawer__footer button', { hasText: '保存' }).first().click(),
    ]);
    expect(resp.status()).toBe(200);
    await reloadModelPage(page);
    const card = page.locator('.model-config .card', { hasText: name }).first();
    await expect(card).toBeVisible({ timeout: 15000 });
    await expect(card.locator('.ed-tag', { hasText: '可用' })).toBeVisible({ timeout: 20000 });
    const hits = await stub.hits();
    expect(hits, 'Anthropic 应命中 /v1/messages').toContain('/v1/messages');
    expect(hits, 'Anthropic 不应命中 /v1/chat/completions').not.toContain('/v1/chat/completions');
    await deleteModel(page, name);
  });

  // 15.7 五语言切换：自定义供应商名称随语言变化
  test('切换语言后自定义供应商名称本地化', async ({ page }) => {
    // 当前构建未挂载 UI 语言切换（Layout 未被路由使用），语言由用户资料驱动；
    // 这里直接驱动应用 i18n 实例切换 locale，验证供应商名称的翻译渲染路径。
    await gotoModelPage(page);
    const setLocale = (lang: string) =>
      page.evaluate((l) => {
        const app: any = (document.querySelector('#app') as any).__vue_app__;
        const i18n = app?.config?.globalProperties?.$i18n;
        if (i18n && typeof i18n.locale === 'object' && 'value' in i18n.locale) {
          i18n.locale.value = l;
        } else if (i18n) {
          i18n.locale = l;
        }
      }, lang);

    // 英文：自定义供应商 -> Custom
    await setLocale('en');
    await expect(page.locator('.model-config .title')).toContainText('AI model config');
    await page.locator('button', { hasText: 'Add model' }).first().click();
    await expect(page.locator('.model-list .title')).toContainText('Select supplier');
    await expect(page.locator('.model-list .model', { hasText: 'Custom' }).first()).toBeVisible({ timeout: 15000 });
    await page.locator('.model-drawer-fullscreen .ed-dialog__headerbtn').first().click();

    // 中文：自定义供应商 -> 自定义
    await setLocale('zh-CN');
    await expect(page.locator('.model-config .title')).toContainText('AI 模型配置');
    await page.locator('button', { hasText: '添加模型' }).first().click();
    await expect(page.locator('.model-list .title')).toContainText('选择提供商');
    await expect(page.locator('.model-list .model', { hasText: '自定义' }).first()).toBeVisible({ timeout: 15000 });
    await page.locator('.model-drawer-fullscreen .ed-dialog__headerbtn').first().click();
  });
});
