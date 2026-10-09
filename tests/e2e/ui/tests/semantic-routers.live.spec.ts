import { test, expect } from '@playwright/test';
import { createHash, randomUUID } from 'node:crypto';
import type { DataStore, SemanticPublication, SemanticRouterDefinition } from '../../../../apps/ui/admin/src/models';
import { downloadDataStore } from '../helpers/data-store-download';
import { SemanticRoutersPage } from '../pages/semantic-routers.page';

test('author, classify, publish, and remove through live Barn and native preview APIs', async ({ page, request }) => {
  test.skip(!process.env.BARN_URL, 'Set BARN_URL to an isolated Barn with the native deterministic preview service configured.');
  const wizard = new SemanticRoutersPage(page);
  const name = `Browser-support-${randomUUID()}`;
  const apiBase = `${process.env.BARN_URL}/api/v1/semantic-routers`;
  const storeApi = `${process.env.BARN_URL}/api/v1/data-store`;
  let definitionId: string | undefined;
  let ownedDefinitionId: string | undefined;
  try {
    await wizard.open();
    await page.getByLabel('Router name', { exact: true }).fill(name);
    await page.getByLabel('Business purpose', { exact: true }).fill('Route support requests through a predefined action.');
    await page.getByLabel('MCP tool name', { exact: true }).fill('browser_support_route');
    await wizard.next();
    await page.getByLabel('Billing support', { exact: true }).check({ force: true });
    await page.getByLabel('Technical support', { exact: true }).check({ force: true });
    await page.getByLabel('Response prefix (required)', { exact: true }).first().fill('');
    await wizard.next();
    await expect(page.getByRole('alert').filter({ hasText: 'Correct these fields' })).toContainText('configuration');
    await page.getByLabel('Response prefix (required)', { exact: true }).first().fill('Browser');
    await wizard.next();
    await page.getByLabel('Configured expert', { exact: true }).selectOption('support');
    await page.getByLabel('Message to classify', { exact: true }).selectOption('message');
    await page.getByLabel('Classification instructions', { exact: true }).fill('Select billing for invoice requests or technical for software requests.');
    const labels = page.getByLabel('Action label', { exact: true });
    await labels.first().fill('billing');
    await labels.nth(1).fill('technical');
    await page.getByLabel('When no action matches', { exact: true }).fill('Other topics do not match.');
    await wizard.next();
    await wizard.example('billing invoice', 'billing');
    await expect(page.getByText('Expected: billing. Actual: billing.', { exact: true })).toBeVisible({ timeout: 20000 });
    await wizard.example('technical software', 'technical');
    await expect(page.getByText('Expected: technical. Actual: technical.', { exact: true })).toBeVisible({ timeout: 20000 });
    await wizard.example('unrelated', 'no_match');
    await expect(page.getByText('Expected: no_match. Actual: no_match.', { exact: true })).toBeVisible({ timeout: 20000 });
    await page.getByRole('button', { name: 'Back', exact: true }).click();
    await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveValue('Select billing for invoice requests or technical for software requests.');
    await wizard.next();
    await wizard.next();
    const revisionsBeforeViewing = await request.get(apiBase);
    expect(revisionsBeforeViewing.ok()).toBeTruthy();
    const existing = await revisionsBeforeViewing.json() as { data: SemanticRouterDefinition[] };
    definitionId = existing.data.find(definition => definition.name === name)?.id;
    ownedDefinitionId = definitionId;
    expect(definitionId).toBeTruthy();
    const before = await request.get(`${apiBase}/${definitionId}/revisions`);
    expect(before.ok()).toBeTruthy();
    expect((await before.json() as { data: SemanticPublication[] }).data).toHaveLength(0);
    const generated = page.getByRole('region', { name: 'Generated draft files', exact: true });
    await expect(page.getByRole('button', { name: 'View generated files', exact: true })).toHaveAttribute('aria-expanded', 'false');
    await expect(generated).toBeHidden();
    await page.getByRole('button', { name: 'View generated files', exact: true }).click();
    await expect(page.getByLabel('Generated file', { exact: true })).toHaveValue('service/router.camel.yaml');
    await expect(generated.locator('code')).toContainText('ai-tool:browser_support_route');
    const snippet = generated.getByRole('textbox');
    await expect(snippet).toHaveCount(1);
    await expect(snippet).toHaveAttribute('aria-readonly', 'true');
    expect(await snippet.evaluate(node => (node as HTMLElement).isContentEditable)).toBe(false);
    await expect(generated.locator('input, textarea, [contenteditable="true"]')).toHaveCount(0);
    await page.getByLabel('Generated file', { exact: true }).selectOption('service/semantic-router.properties');
    await expect(generated.locator('code')).toContainText('catalog.revision=preview');
    const after = await request.get(`${apiBase}/${definitionId}/revisions`);
    expect(after.ok()).toBeTruthy();
    expect((await after.json() as { data: SemanticPublication[] }).data).toHaveLength(0);
    await page.getByRole('button', { name: 'View generated files', exact: true }).click();
    await page.getByRole('button', { name: 'Publish catalog', exact: true }).click();
    await expect(page.getByText('Catalog published', { exact: true })).toBeVisible({ timeout: 20000 });
    await expect(page.getByText(/Runtime status: not observed/)).toBeVisible();
    const definitions = await request.get(apiBase);
    expect(definitions.ok()).toBeTruthy();
    const body = await definitions.json() as { data: SemanticRouterDefinition[] };
    definitionId = body.data.find(definition => definition.name === name)?.id;
    ownedDefinitionId = definitionId;
    expect(definitionId).toBeTruthy();
    const revisions = await request.get(`${apiBase}/${definitionId}/revisions`);
    expect(revisions.ok()).toBeTruthy();
    const revisionBody = await revisions.json() as { data: SemanticPublication[] };
    expect(revisionBody.data).toHaveLength(1);
    expect(revisionBody.data[0].status).toBe('published');
    const publication = revisionBody.data[0];
    await page.getByRole('button', { name: 'Close', exact: true }).last().click();
    const stores = await request.get(storeApi);
    expect(stores.ok()).toBeTruthy();
    const storeBody = await stores.json() as { data: DataStore[] };
    const catalog = storeBody.data.find(entry => entry.name === publication.catalogName);
    const draft = storeBody.data.find(entry => entry.id === definitionId);
    const metadata = storeBody.data.find(entry => entry.labels?.['semantic.definition'] === definitionId && entry.labels?.['wanaku.type'] === 'semantic-publication');
    expect(catalog?.id).toBeTruthy();
    expect(catalog?.labels?.['wanaku.type']).toBe('catalog');
    expect(draft?.labels?.['wanaku.type']).toBe('semantic-definition');
    expect(metadata?.id).toBeTruthy();
    await page.goto('./#/data-stores');
    const archive = await downloadDataStore(page, publication.catalogName!);
    expect(archive.filename).toBe(`${publication.catalogName}.zip`);
    expect(archive.bytes.subarray(0, 4)).toEqual(Buffer.from([0x50, 0x4b, 0x03, 0x04]));
    expect(createHash('sha256').update(archive.bytes).digest('hex')).toBe(publication.sha256);
    expect(archive.bytes).toEqual(Buffer.from(catalog!.data!, 'base64'));
    for (const entry of [draft!, metadata!]) {
      const downloaded = await downloadDataStore(page, entry.name!);
      expect(downloaded.filename).toBe(`${entry.name}.json`);
      expect(downloaded.bytes).toEqual(Buffer.from(entry.data!, 'utf8'));
      expect(JSON.parse(downloaded.bytes.toString('utf8'))).toEqual(JSON.parse(entry.data!));
    }
    await page.goto('./#/semantic-routers');
    await page.getByRole('button', { name: `Remove ${name}`, exact: true }).click();
    await page.getByRole('button', { name: 'Remove', exact: true }).click();
    await expect(page.getByRole('button', { name: `Edit ${name}`, exact: true })).not.toBeVisible();
    const removed = await request.get(`${apiBase}/${definitionId}`);
    expect(removed.status()).toBe(404);
    definitionId = undefined;
  } finally {
    // Remove only this test's draft and publication metadata. Catalogs are immutable.
    if (!definitionId) {
      const response = await request.get(apiBase);
      if (response.ok()) {
        const body = await response.json() as { data: SemanticRouterDefinition[] };
        definitionId = body.data.find(definition => definition.name === name)?.id;
      }
    }
    const ownerId = ownedDefinitionId ?? definitionId;
    if (ownerId) {
      const stores = await request.get(storeApi);
      if (stores.ok()) {
        const body = await stores.json() as { data: DataStore[] };
        for (const entry of body.data.filter(entry => entry.labels?.['semantic.definition'] === ownerId && entry.labels?.['wanaku.type'] === 'semantic-publication')) {
          const removed = await request.delete(`${storeApi}/${entry.id}`);
          expect([200, 404]).toContain(removed.status());
        }
      }
    }
    if (definitionId) await request.delete(`${apiBase}/${definitionId}`);
  }
});
