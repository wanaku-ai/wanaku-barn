import { readFile } from 'node:fs/promises';
import { expect, type Page } from '@playwright/test';

export async function downloadDataStore(page: Page, name: string) {
  const row = page.getByRole('row').filter({ has: page.getByRole('cell', { name, exact: true }) });
  const pendingDownload = page.waitForEvent('download');
  await row.getByRole('button', { name: 'Download', exact: true }).click();
  const download = await pendingDownload;
  expect(await download.failure()).toBeNull();
  const path = await download.path();
  if (!path) throw new Error('Browser did not save the downloaded file.');
  return { filename: download.suggestedFilename(), bytes: await readFile(path) };
}
