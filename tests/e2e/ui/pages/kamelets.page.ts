import { expect, type Page } from '@playwright/test';

export class KameletsPage {
  constructor(readonly page: Page) {}
  async open() {
    await this.page.goto('./#/kamelets');
    await expect(this.page.getByRole('heading', { name: 'Kamelets', exact: true })).toBeVisible();
  }
  async navigate() {
    await this.page.getByRole('navigation', { name: 'Wanaku', exact: true }).getByRole('link', { name: 'Kamelets', exact: true }).click();
    await expect(this.page.getByRole('heading', { name: 'Kamelets', exact: true })).toBeVisible();
  }
  async selectFile(yaml: string | Buffer, filename = 'upload.kamelet.yaml') {
    await this.page.getByRole('button', { name: 'Upload Kamelet', exact: true }).click();
    const dialog = this.page.getByRole('dialog', { name: 'Upload Kamelet', exact: true });
    await expect(dialog).toBeVisible();
    await dialog.locator('input[type="file"]').setInputFiles({ name: filename, mimeType: 'application/yaml', buffer: Buffer.isBuffer(yaml) ? yaml : Buffer.from(yaml, 'utf8') });
    return dialog;
  }
  async upload(yaml: string, filename?: string) {
    const dialog = await this.selectFile(yaml, filename);
    await dialog.getByRole('button', { name: 'Upload', exact: true }).click();
    await expect(dialog).toBeHidden();
  }
}
