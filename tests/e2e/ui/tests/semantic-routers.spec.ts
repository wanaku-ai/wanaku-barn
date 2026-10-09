import { test, expect, type Page } from '@playwright/test';
import { semanticRouterFixture } from '../helpers/semantic-router-fixture';
import { SemanticRoutersPage } from '../pages/semantic-routers.page';

test('create, validate, preview, publish, reopen, and remove a semantic router', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.next();
  await expect(page.getByRole('alert').filter({ hasText: 'Correct these fields' })).toContainText('name: Value is required');
  await wizard.identity();
  await expect(page.getByLabel('Incompatible action', { exact: true })).toBeDisabled();
  await page.getByLabel('Billing support', { exact: true }).check({ force: true });
  await page.getByLabel('Technical support', { exact: true }).check({ force: true });
  const prefixes = page.getByLabel('Response prefix (required)', { exact: true });
  await prefixes.first().fill('');
  await wizard.next();
  await expect(page.getByRole('alert').filter({ hasText: 'Correct these fields' })).toContainText('Response prefix is required');
  await prefixes.first().fill('Support');
  await page.getByLabel('Credential reference', { exact: true }).first().fill('env:SUPPORT_TOKEN');
  await wizard.next();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByLabel('Credential reference', { exact: true }).first()).toHaveValue('env:SUPPORT_TOKEN');
  await wizard.next();
  await expect(page.getByLabel('Message to classify', { exact: true })).toHaveValue('message');
  await expect(page.getByLabel('Message to classify', { exact: true })).toHaveAccessibleDescription(/MCP request message field/);
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAccessibleDescription(/Ask the business question that decides which action label to return/);
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAttribute('aria-invalid', 'true');
  await expect(page.getByText(/Ask the business question that decides which action label to return/)).toBeVisible();
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAttribute('placeholder', 'Which team should handle this support request? If the state is an envelope, classify `message` and use `serviceScope` only as background context.');
  await wizard.decision();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAttribute('aria-invalid', 'false');
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAccessibleDescription(/Ask the business question that decides which action label to return/);
  await expect(page.getByText(/Ask the business question that decides which action label to return/)).toBeVisible();
  await wizard.next();
  await wizard.example('My invoice needs a refund', 'wsr_billing_action');
  await expect(page.getByText('Expected: wsr_billing_action. Actual: wsr_billing_action.', { exact: true })).toBeVisible();
  await wizard.example('Software is unavailable', 'wsr_technical_action');
  await expect(page.getByText('Expected: wsr_technical_action. Actual: wsr_technical_action.', { exact: true })).toBeVisible();
  await wizard.example('The weather is sunny', 'no_match');
  await expect(page.getByText('Expected: no_match. Actual: no_match.', { exact: true })).toBeVisible();
  await wizard.next();
  await page.getByRole('button', { name: 'Publish catalog', exact: true }).click();
  await expect(page.getByText('Revision: revision-001', { exact: true })).toBeVisible();
  await expect(page.getByText(/Runtime status: not observed/)).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Deployment instructions' })).toBeVisible();
  await expect(page.getByText("java -jar wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route='Support-routing'", { exact: true })).toBeVisible();
  await expect(page.getByText(/Set TYPESAFE_API_KEY in the runtime environment/)).toBeVisible();
  expect(fixture.previews).toHaveLength(3);
  expect([...fixture.definitions.values()][0].examples).toHaveLength(3);
  await page.getByRole('button', { name: 'Close', exact: true }).last().click();
  await page.getByRole('button', { name: 'Edit Support-routing', exact: true }).click();
  await expect(page.getByLabel('Router name', { exact: true })).toHaveValue('Support-routing');
  await page.getByRole('button', { name: 'Close', exact: true }).last().click();
  await page.getByRole('button', { name: 'Remove Support-routing', exact: true }).click();
  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByText(/No semantic routers yet/)).toBeVisible();
  expect(fixture.definitions.size).toBe(0);
});

test('show provider failure and malformed decisions separately from no match', async ({ page }) => {
  await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity();
  await wizard.actions();
  await wizard.decision();
  await wizard.example('provider_error', 'no_match');
  await expect(page.getByText('Expert provider is unavailable', { exact: true })).toBeVisible();
  await expect(page.getByText('No action matched', { exact: true })).not.toBeVisible();
  await wizard.example('malformed', 'no_match');
  await expect(page.getByText('Malformed expert decision', { exact: true })).toBeVisible();
});

test('save an incomplete draft and navigate using the keyboard', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await page.getByLabel('Router name', { exact: true }).fill('Draft-only');
  await page.getByRole('button', { name: 'Save draft', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  expect(fixture.definitions.size).toBe(1);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Edit Draft-only', exact: true }).click();
  await expect(page.getByLabel('Router name', { exact: true })).toHaveValue('Draft-only');
  await page.getByLabel('Business purpose', { exact: true }).fill('Draft purpose');
  await page.getByLabel('MCP tool name', { exact: true }).fill('draft_only');
  await wizard.next();
  await expect(page.getByRole('heading', { name: 'Actions', exact: true })).toBeFocused();
  await page.getByRole('button', { name: 'Back', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByLabel('Business purpose', { exact: true })).toHaveValue('Draft purpose');
});


async function savedPublicationFixture(page: Page, name = 'Support-routing') {
  const fixture = await semanticRouterFixture(page);
  const id = 'saved-router';
  fixture.definitions.set(id, {
    id, name, description: 'Route requests', toolName: 'support_route', profile: 'message-to-string/v1',
    expertId: 'support', semanticInput: 'message', instructions: 'Select one action.', noMatchCriteria: 'Other requests.',
    actions: ['billing', 'technical'].map(kind => ({
      actionId: `wsr-${kind}-action`, label: kind, criteria: `${kind} requests`, configuration: { prefix: 'Support' },
    })), examples: [],
  });
  const current = {
    definitionId: id, revision: `r${'a'.repeat(24)}`, catalogName: 'current-catalog', sha256: 'a'.repeat(64),
    mainFile: 'routes/main.yaml', camelVersion: '4.23.0-SNAPSHOT', status: 'published', toolName: 'support_route',
    expert: { id: 'support', name: 'Support expert', bean: 'supportExpert', dependency: 'org.apache.camel:camel-typesafe-ai:4.23.0-SNAPSHOT' },
    deploymentInstructions: ['OLD_MANUAL_CATALOG_SETTINGS'],
  };
  const older = { ...current, revision: `r${'f'.repeat(24)}`, catalogName: 'older-catalog', sha256: 'f'.repeat(64) };
  fixture.publications.set(id, [current, older]);
  fixture.currentPublications.set(id, current.revision);
  return { ...fixture, id, current, older, name };
}

async function reviewSavedRouter(page: Page, name: string, unsavedName?: string) {
  await page.goto('./#/semantic-routers');
  await page.getByRole('button', { name: `Edit ${name}`, exact: true }).click();
  if (unsavedName) await page.getByLabel('Router name', { exact: true }).fill(unsavedName);
  const wizard = new SemanticRoutersPage(page);
  for (let step = 0; step < 4; step++) await wizard.next();
  await expect(page.getByRole('heading', { name: 'Review and publication', exact: true })).toBeVisible();
}

test('reopened review shows the resolved current pointer instead of the last hash-sorted revision', async ({ page }) => {
  const fixture = await savedPublicationFixture(page);
  await reviewSavedRouter(page, fixture.name);
  const published = page.getByRole('region', { name: 'Published catalog', exact: true });
  await expect(published).toContainText(`Revision: ${fixture.current.revision}`);
  await expect(published).toContainText('Catalog: current-catalog');
  await expect(published).toContainText(fixture.current.sha256);
  await expect(published).not.toContainText(fixture.older.revision);
  expect(fixture.resolutions).toEqual([fixture.name]);
});

test('launch guidance quotes the saved route name and stays independent of unsaved edits and old instructions', async ({ page }) => {
  const name = "Support team's $(printf unsafe); router";
  const fixture = await savedPublicationFixture(page, name);
  await reviewSavedRouter(page, name, 'Unsaved-replacement-name');
  const published = page.getByRole('region', { name: 'Published catalog', exact: true });
  const command = `java -jar wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route='Support team'"'"'s $(printf unsafe); router'`;
  await expect(published.getByText(command, { exact: true })).toBeVisible();
  await expect(published).not.toContainText('Unsaved-replacement-name');
  await expect(published).toContainText('Set TYPESAFE_API_KEY in the runtime environment.');
  await expect(published).toContainText(`--catalog-revision='${fixture.current.revision}'`);
  await page.getByRole('button', { name: 'Recorded deployment settings', exact: true }).click();
  await expect(published.getByText('OLD_MANUAL_CATALOG_SETTINGS', { exact: true })).toBeVisible();
  expect(fixture.resolutions).toEqual([name]);
});

for (const status of [404, 409]) {
  test(`reopened review reports unobserved current publication for resolver ${status} without guessing a revision`, async ({ page }) => {
    const fixture = await savedPublicationFixture(page);
    fixture.resolutionErrors.set(fixture.id, status);
    await reviewSavedRouter(page, fixture.name);
    await expect(page.getByText('Current publication not observed', { exact: true })).toBeVisible();
    await expect(page.getByRole('region', { name: 'Published catalog', exact: true })).toHaveCount(0);
    await expect(page.getByText(/java -jar wanaku-semantic-router/)).toHaveCount(0);
    expect(fixture.resolutions).toEqual([fixture.name]);
  });
}


for (const field of ['Router name', 'MCP tool name']) {
  test(`${field} rejects leading digits, spaces, punctuation and non-ASCII letters before save or advance`, async ({ page }) => {
    const fixture = await semanticRouterFixture(page);
    const wizard = new SemanticRoutersPage(page);
    await wizard.open();
    await page.getByLabel('Router name', { exact: true }).fill('Support-routing');
    await page.getByLabel('Business purpose', { exact: true }).fill('Route support requests.');
    await page.getByLabel('MCP tool name', { exact: true }).fill('support_route');
    for (const value of ['9Support', 'Support routing', '_Support', 'Support.route', 'Équipe', '   ']) {
      const input = page.getByLabel(field, { exact: true });
      await input.fill(value);
      await expect(input).toHaveAttribute('aria-invalid', 'true');
      await expect(input).toHaveAccessibleDescription(/Start with a letter/);
      await wizard.next();
      await expect(page.getByRole('heading', { name: 'Identity', exact: true })).toBeVisible();
      await page.getByRole('button', { name: 'Save draft', exact: true }).click();
      await expect(page.getByRole('button', { name: 'Save draft', exact: true })).toBeEnabled();
      await expect(page.getByText('Draft saved', { exact: true })).toHaveCount(0);
      expect(fixture.definitions.size).toBe(0);
      expect(fixture.saves).toHaveLength(0);
    }
  });
}

for (const [name, toolName] of [['Support-ROUTER_2', 'Support-TOOL_2'], ['R'.repeat(120), 'T'.repeat(64)]]) {
  test(`uppercase names, hyphens, underscores and exact length limits are accepted (${name.length}/${toolName.length})`, async ({ page }) => {
    const fixture = await semanticRouterFixture(page);
    const wizard = new SemanticRoutersPage(page);
    await wizard.open();
    await expect(page.getByLabel('Router name', { exact: true })).toHaveAttribute('maxlength', '120');
    await expect(page.getByLabel('MCP tool name', { exact: true })).toHaveAttribute('maxlength', '64');
    await wizard.identity(name, toolName);
    await expect(page.getByRole('heading', { name: 'Actions', exact: true })).toBeVisible();
    await page.getByRole('button', { name: 'Save draft', exact: true }).click();
    await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
    expect([...fixture.definitions.values()][0]).toMatchObject({ name, toolName });
  });
}

test('an empty draft remains saveable', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  await new SemanticRoutersPage(page).open();
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  expect([...fixture.definitions.values()][0]).toMatchObject({ name: '', toolName: '' });
});

async function expectTextboxHints(page: Page) {
  for (const textbox of await page.getByRole('textbox').all()) {
    await expect(textbox).toHaveAttribute('placeholder', /\S/);
    await expect(textbox).toHaveAccessibleDescription(/\S/);
  }
}

test('every authoring textbox has guidance, including native schema fields without descriptions', async ({ page }) => {
  await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await expectTextboxHints(page);
  await wizard.identity();
  await page.getByLabel('Billing support', { exact: true }).check({ force: true });
  await page.getByLabel('Technical support', { exact: true }).check({ force: true });
  await expectTextboxHints(page);
  await expect(page.getByLabel('Response prefix (required)', { exact: true }).first()).toHaveAccessibleDescription(/Set Response prefix for this action/);
  await expect(page.getByLabel('Credential reference', { exact: true }).first()).toHaveAccessibleDescription(/Do not enter a credential value/);
  await wizard.next();
  await expectTextboxHints(page);
  await expect(page.getByLabel('Classification instructions', { exact: true })).toHaveAttribute('placeholder', 'Which team should handle this support request? If the state is an envelope, classify `message` and use `serviceScope` only as background context.');
  await expect(page.getByLabel('When no action matches', { exact: true })).toHaveAttribute('placeholder', 'Assign to other and log the request');
  await wizard.decision();
  await page.getByRole('button', { name: 'Add example', exact: true }).click();
  await expectTextboxHints(page);
  await expect(page.getByLabel('Example 1 message', { exact: true })).toHaveAccessibleDescription(/expert classifies/);
});

test('generated files open by keyboard, select one read-only file at a time, and never save or publish', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity();
  await wizard.actions();
  await wizard.decision();
  await wizard.next();
  const viewer = page.getByRole('button', { name: 'View generated files', exact: true });
  const region = page.getByRole('region', { name: 'Generated draft files', exact: true });
  await expect(viewer).toHaveAttribute('aria-expanded', 'false');
  await expect(region).toBeHidden();
  expect(fixture.fileRequests).toHaveLength(0);
  await viewer.focus();
  await page.keyboard.press('Enter');
  await expect(viewer).toHaveAttribute('aria-expanded', 'true');
  const selected = page.getByLabel('Generated file', { exact: true });
  await expect(selected).toHaveValue('service/router.camel.yaml');
  await expect(selected).toHaveAccessibleDescription(/read-only/);
  const code = region.locator('code');
  await expect(code).toContainText('ai-tool:support_route');
  const snippet = region.getByRole('textbox');
  await expect(snippet).toHaveCount(1);
  await expect(snippet).toHaveAttribute('aria-readonly', 'true');
  expect(await snippet.evaluate(node => (node as HTMLElement).isContentEditable)).toBe(false);
  await expect(region.locator('input, textarea')).toHaveCount(0);
  await expect(region.locator('[contenteditable="true"]')).toHaveCount(0);
  const originalContent = await code.innerText();
  await snippet.focus();
  await page.keyboard.type('Attempted draft edit');
  await expect(code).toHaveText(originalContent);
  const files = {
    'index.properties': 'catalog.name=semantic-preview',
    'service/dependencies.txt': 'camel:semantic',
    'service/kamelets/wsr-billing-action.kamelet.yaml': 'name: wsr-billing-action',
    'service/kamelets/wsr-technical-action.kamelet.yaml': 'name: wsr-technical-action',
    'service/preview.camel.yaml': 'direct:classify-router',
    'service/router.camel.yaml': 'ai-tool:support_route',
    'service/semantic-router.properties': 'catalog.revision=preview',
    'service/service.properties': 'action.wsr_billing_action.prefix=Support',
  };
  expect(await selected.locator('option').allTextContents()).toEqual(Object.keys(files).sort());
  await selected.focus();
  await expect(selected).toBeFocused();
  // Native select typeahead chooses the index file without opening a platform-specific picker.
  await page.keyboard.press('i');
  await expect(selected).toHaveValue('index.properties');
  await expect(code).toContainText('catalog.name=semantic-preview');
  for (const [path, content] of Object.entries(files)) {
    await selected.selectOption(path);
    await expect(code).toContainText(content);
    if (path === 'service/preview.camel.yaml') await expect(code).not.toContainText('ai-tool:');
  }
  await viewer.focus();
  await page.keyboard.press('Space');
  await expect(viewer).toHaveAttribute('aria-expanded', 'false');
  await expect(region).toBeHidden();
  expect(fixture.fileRequests).toHaveLength(1);
  expect(fixture.saves).toHaveLength(0);
  expect(fixture.previews).toHaveLength(0);
  expect(fixture.publishRequests).toHaveLength(0);
  expect(fixture.definitions.size).toBe(0);
  expect(fixture.publications.size).toBe(0);
});

test('generated file failures stay visible and retry without publishing', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  fixture.fileStatuses.push(503);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity();
  await wizard.actions();
  await wizard.decision();
  await wizard.next();
  await page.getByRole('button', { name: 'View generated files', exact: true }).click();
  const region = page.getByRole('region', { name: 'Generated draft files', exact: true });
  await expect(region.getByText('Cannot generate files', { exact: true })).toBeVisible();
  await expect(region.getByText('Generated files are temporarily unavailable', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Generated file', { exact: true })).toHaveCount(0);
  await region.getByRole('button', { name: 'Retry generated files', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(page.getByLabel('Generated file', { exact: true })).toBeVisible();
  await expect(region.getByText('Cannot generate files', { exact: true })).toHaveCount(0);
  expect(fixture.fileRequests).toHaveLength(2);
  expect(fixture.saves).toHaveLength(0);
  expect(fixture.previews).toHaveLength(0);
  expect(fixture.publishRequests).toHaveLength(0);
});

test('generated files are closed and regenerated from fresh unsaved decision edits', async ({ page }) => {
  const fixture = await semanticRouterFixture(page);
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity();
  await wizard.actions();
  await wizard.decision();
  await wizard.next();
  await page.getByRole('button', { name: 'View generated files', exact: true }).click();
  const region = page.getByRole('region', { name: 'Generated draft files', exact: true });
  await expect(region.locator('code')).toContainText('Select one action that matches the request.');
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByLabel('Classification instructions', { exact: true }).fill('Use the updated escalation policy.');
  await wizard.next();
  await wizard.next();
  await expect(page.getByRole('button', { name: 'View generated files', exact: true })).toHaveAttribute('aria-expanded', 'false');
  await expect(region).toBeHidden();
  await page.getByRole('button', { name: 'View generated files', exact: true }).click();
  await expect(region.locator('code')).toContainText('Use the updated escalation policy.');
  await expect(region.locator('code')).not.toContainText('Select one action that matches the request.');
  expect(fixture.fileRequests).toHaveLength(2);
  expect(fixture.fileRequests[1].instructions).toBe('Use the updated escalation policy.');
  expect(fixture.saves).toHaveLength(0);
  expect(fixture.previews).toHaveLength(0);
  expect(fixture.publishRequests).toHaveLength(0);
});
