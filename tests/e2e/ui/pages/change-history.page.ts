import { expect, type Page } from '@playwright/test';

export class ChangeHistoryPage {
  constructor(readonly page: Page) {}
  async open(query = '') {
    await this.page.goto(`./#/change-history${query}`);
    await expect(this.page.getByRole('heading', { name: 'Change History', exact: true })).toBeVisible();
  }
  resource() { return this.page.getByLabel('Resource', { exact: true }); }
  rows() { return this.page.getByRole('table', { name: 'Change history events' }).locator('tbody tr'); }
}
