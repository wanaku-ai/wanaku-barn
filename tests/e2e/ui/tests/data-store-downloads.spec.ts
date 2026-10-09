import { test, expect, type Page } from '@playwright/test';
import type { DataStore } from '../../../../apps/ui/admin/src/models';
import { downloadDataStore } from '../helpers/data-store-download';

async function storedFile(page: Page, entry: DataStore, response: DataStore = entry, status = 200) {
  const requestedIds: string[] = [];
  await page.route('**/api/v1/data-store**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/v1/data-store') return route.fulfill({ json: { data: [entry] } });
    requestedIds.push(path.substring('/api/v1/data-store/'.length));
    await route.fulfill({ status, json: status === 200 ? { data: response } : { error: { message: 'Stored file is unavailable.' } } });
  });
  await page.goto('./#/data-stores');
  await expect(page.getByRole('heading', { name: 'Data Stores', exact: true })).toBeVisible();
  return requestedIds;
}

test('download the persisted semantic catalog as ZIP without changing binary bytes', async ({ page }) => {
  const bytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x00, 0xff, 0x80, 0x0a]);
  const entry: DataStore = { id: 'catalog-1', name: 'semantic-support-r1', labels: { 'wanaku.type': 'catalog', 'semantic.immutable': 'true' } };
  const requests = await storedFile(page, entry, { ...entry, data: bytes.toString('base64') });
  const download = await downloadDataStore(page, entry.name!);
  expect(download.filename).toBe('semantic-support-r1.zip');
  expect(download.bytes).toEqual(bytes);
  expect(requests).toEqual(['catalog-1']);
});

for (const type of ['semantic-definition', 'semantic-publication', 'semantic-current-publication']) {
  test(`download ${type} as unchanged UTF-8 JSON`, async ({ page }) => {
    const record = type === 'semantic-current-publication'
      ? { revision: `r${'a'.repeat(24)}` }
      : { name: 'Support café', instructions: 'Handle invoices — choose billing.' };
    const data = JSON.stringify(record);
    const entry: DataStore = { id: type, name: type, data, labels: { 'wanaku.type': type } };
    await storedFile(page, entry);
    const download = await downloadDataStore(page, entry.name!);
    expect(download.filename).toBe(`${type}.json`);
    expect(download.bytes).toEqual(Buffer.from(data, 'utf8'));
    expect(JSON.parse(download.bytes.toString('utf8'))).toEqual(JSON.parse(data));
    await page.getByRole('button', { name: 'View', exact: true }).click();
    await expect(page.getByRole('dialog').locator('pre')).toHaveText(JSON.stringify(record, null, 2));
    await expect(page.getByRole('dialog')).not.toContainText('Failed to decode data');
  });
}

test('preserve uploaded filenames and allow downloads for a row without an ID', async ({ page }) => {
  const bytes = Buffer.from([0x00, 0xfe, 0xff, 0x80]);
  const entry = { name: 'uploaded-file.bin', data: bytes.toString('base64') };
  const requests = await storedFile(page, entry);
  const download = await downloadDataStore(page, entry.name);
  expect(download.filename).toBe(entry.name);
  expect(download.bytes).toEqual(bytes);
  expect(requests).toEqual([]);
});

for (const failure of ['missing', 'corrupt', 'api-error']) {
  test(`show a visible download error for ${failure} data`, async ({ page }) => {
    const entry = { id: 'broken-file', name: 'broken-file.bin' };
    await storedFile(page, entry, { ...entry, data: failure === 'corrupt' ? '{not-base64}' : undefined }, failure === 'api-error' ? 404 : 200);
    let downloads = 0;
    page.on('download', () => downloads++);
    await page.getByRole('button', { name: 'Download', exact: true }).click();
    const error = page.getByRole('status').filter({ hasText: 'Failed to download file.' });
    await expect(error).toBeVisible();
    if (failure === 'api-error') await expect(error).toContainText('Stored file is unavailable.');
    if (failure === 'missing') await expect(error).toContainText('No data is available');
    await expect(page.getByRole('button', { name: 'Download', exact: true })).toBeEnabled();
    expect(downloads).toBe(0);
  });
}
