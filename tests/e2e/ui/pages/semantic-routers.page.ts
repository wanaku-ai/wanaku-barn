import { expect, type Page } from '@playwright/test';

export class SemanticRoutersPage {
  constructor(readonly page: Page) {}
  async open() {
    await this.page.goto('./#/semantic-routers');
    await expect(this.page.getByRole('heading', { name: 'Semantic Routers', exact: true })).toBeVisible();
    await this.page.getByRole('button', { name: 'Create semantic router', exact: true }).click();
  }
  async identity(name = 'Support-routing', toolName = 'support_route') {
    await this.page.getByLabel('Router name', { exact: true }).fill(name);
    await this.page.getByLabel('Business purpose', { exact: true }).fill('Route support requests to the appropriate team.');
    await this.page.getByLabel('MCP tool name', { exact: true }).fill(toolName);
    await this.next();
  }
  async actions() {
    await this.page.getByLabel('Billing support', { exact: true }).check({ force: true });
    await this.page.getByLabel('Technical support', { exact: true }).check({ force: true });
    await this.next();
  }
  async decision() {
    await this.page.getByLabel('Configured expert', { exact: true }).selectOption('support');
    await this.page.getByLabel('Message to classify', { exact: true }).selectOption('message');
    await this.page.getByLabel('Classification instructions', { exact: true }).fill('Select one action that matches the request.');
    await this.page.getByLabel('When no action matches', { exact: true }).fill('Unrelated requests.');
    await this.next();
  }
  async example(message: string, label: string) {
    await this.page.getByRole('button', { name: 'Add example', exact: true }).click();
    const messages = this.page.getByLabel(/Example \d+ message/);
    const index = await messages.count();
    await messages.nth(index - 1).fill(message);
    await this.page.getByLabel(`Example ${index} expected label`, { exact: true }).selectOption(label);
    await this.page.getByRole('button', { name: `Preview example ${index}`, exact: true }).click();
  }
  async next() { await this.page.getByRole('button', { name: 'Next', exact: true }).click(); }
}
