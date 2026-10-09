import { test, expect } from '@playwright/test';
import { readFile } from 'node:fs/promises';
import { kameletFixture } from '../helpers/kamelet-fixture';
import { KameletsPage } from '../pages/kamelets.page';
import { SemanticRoutersPage } from '../pages/semantic-routers.page';

test('list ordinary and semantic Kamelets, search metadata, and protect bundled entries', async ({ page }) => {
  const fixture = await kameletFixture(page);
  fixture.seed('timer-source', { title: 'Timer source', description: 'Emit timer ticks', type: 'source', source: 'bundled' });
  fixture.seed('remote-support-action', { title: 'Remote support café', eligible: true });
  await new KameletsPage(page).open();
  await expect(page.getByRole('heading', { name: 'Timer source', exact: true })).toBeVisible();
  await expect(page.getByText('Not eligible for semantic routing', { exact: true })).toBeVisible();
  await expect(page.getByText('Semantic action', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Remove timer-source', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Upload Kamelet', exact: true })).toBeVisible();
  await page.getByRole('searchbox', { name: 'Search Kamelets', exact: true }).fill('CAFÉ');
  await expect(page.getByRole('heading', { name: 'Remote support café', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Timer source', exact: true })).toHaveCount(0);
  await page.getByRole('searchbox', { name: 'Search Kamelets', exact: true }).fill('unlisted');
  await expect(page.getByText('No Kamelets match your search.', { exact: true })).toBeVisible();
});

test('upload ordinary and eligible native files, derive names from metadata, and download exact UTF-8 bytes', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const ordinary = fixture.define('ordinary-action', { title: 'Ordinary action' });
  const eligible = fixture.define('remote-support-action', { title: 'Remote support café', eligible: true });
  const catalog = new KameletsPage(page);
  await catalog.open();
  await catalog.upload(ordinary.definition.yaml!, 'unrelated-filename.kamelet.yaml');
  await expect(page.getByRole('heading', { name: 'Ordinary action', exact: true })).toBeVisible();
  await expect(page.getByText('Not eligible for semantic routing', { exact: true })).toBeVisible();
  await catalog.upload(eligible.definition.yaml!, 'second-file.kamelet.yaml');
  await expect(page.getByRole('heading', { name: 'Remote support café', exact: true })).toBeVisible();
  await expect(page.getByText('Semantic action', { exact: true })).toBeVisible();
  const pending = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download remote-support-action', exact: true }).click();
  const download = await pending;
  expect(await download.failure()).toBeNull();
  expect(download.suggestedFilename()).toBe('remote-support-action.kamelet.yaml');
  const path = await download.path();
  expect(path).toBeTruthy();
  expect(await readFile(path!)).toEqual(Buffer.from(eligible.definition.yaml!, 'utf8'));
  expect(fixture.downloads).toEqual([{ name: 'remote-support-action', sha256: eligible.definition.sha256 }]);
  expect(fixture.uploads).toEqual([ordinary.definition.yaml, eligible.definition.yaml]);
});

test('keyboard file chooser selects Kamelet YAML and a failed upload retains it for retry', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const candidate = fixture.define('retry-action', { title: 'Retry action', eligible: true });
  fixture.uploadErrors.push({ status: 422, message: 'Native Kamelet validation failed' });
  const catalog = new KameletsPage(page);
  await catalog.open();
  await page.getByRole('button', { name: 'Upload Kamelet', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: 'Upload Kamelet', exact: true });
  await expect(dialog).toBeVisible();
  await dialog.getByRole('button', { name: 'Select or drop a Kamelet file', exact: true }).and(dialog.locator('button')).focus();
  const pendingChooser = page.waitForEvent('filechooser');
  await page.keyboard.press('Enter');
  const chooser = await pendingChooser;
  expect(chooser.isMultiple()).toBe(false);
  await chooser.setFiles({
    name: 'retry.KAMELET.YAML', mimeType: 'application/yaml', buffer: Buffer.from(candidate.definition.yaml!, 'utf8'),
  });
  await expect(dialog.getByText(/Barn reads the Kamelet name from its metadata/)).toBeVisible();
  await expect(dialog.getByRole('textbox')).toHaveCount(0);
  await expect(dialog.locator('input[type="file"]')).toHaveAttribute('accept', '.yaml');
  await expect(dialog.locator('input[type="file"]')).not.toHaveAttribute('multiple', /.*/);
  await dialog.getByRole('button', { name: 'Upload', exact: true }).click();
  await expect(dialog.getByText('Upload failed', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Native Kamelet validation failed', { exact: true })).toBeVisible();
  await expect(dialog.getByText('retry.KAMELET.YAML', { exact: true })).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Upload', exact: true })).toBeEnabled();
  await dialog.getByRole('button', { name: 'Upload', exact: true }).focus();
  await page.keyboard.press('Enter');
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Retry action', exact: true })).toBeVisible();
  expect(fixture.uploads).toEqual([candidate.definition.yaml, candidate.definition.yaml]);
});

for (const [failure, file] of [
  ['wrong extension', { name: 'native.yaml', bytes: Buffer.from('kind: Kamelet') }],
  ['too large', { name: 'large.kamelet.yaml', bytes: Buffer.alloc(1024 * 1024 + 1, 65) }],
  ['invalid UTF-8', { name: 'invalid.kamelet.yaml', bytes: Buffer.from([0xc3, 0x28]) }],
] as const) {
  test(`reject ${failure} files without sending YAML to Barn`, async ({ page }) => {
    const fixture = await kameletFixture(page);
    const catalog = new KameletsPage(page);
    await catalog.open();
    const dialog = await catalog.selectFile(file.bytes, file.name);
    if (failure === 'wrong extension') {
      await expect(dialog.getByText('Select a .kamelet.yaml file.', { exact: true })).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Upload', exact: true })).toBeDisabled();
    } else if (failure === 'too large') {
      await expect(dialog.getByText(/1 MiB or less/).last()).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Upload', exact: true })).toBeDisabled();
    } else {
      await dialog.getByRole('button', { name: 'Upload', exact: true }).click();
      await expect(dialog.getByText('Upload failed', { exact: true })).toBeVisible();
      await expect(dialog.getByText(file.name, { exact: true })).toBeVisible();
    }
    expect(fixture.uploads).toHaveLength(0);
    expect(fixture.current.size).toBe(0);
  });
}

test('cancel removal, retain the card on failure, and remove only the current listing after confirmation', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const entry = fixture.seed('removable-action', { title: 'Removable action', eligible: true });
  await new KameletsPage(page).open();
  await page.getByRole('button', { name: 'Remove removable-action', exact: true }).click();
  let dialog = page.getByRole('dialog', { name: 'Remove Kamelet?', exact: true });
  await expect(dialog.getByText(/Existing routers and published catalogs keep their selected revision/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect(dialog).toBeHidden();
  expect(fixture.removals).toHaveLength(0);
  await expect(page.getByRole('heading', { name: 'Removable action', exact: true })).toBeVisible();
  fixture.removalErrors.set('removable-action', 503);
  await page.getByRole('button', { name: 'Remove removable-action', exact: true }).click();
  dialog = page.getByRole('dialog', { name: 'Remove Kamelet?', exact: true });
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(dialog.getByText('Removal failed', { exact: true })).toBeVisible();
  await expect(dialog.getByText('Kamelet removal is unavailable', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Removable action', exact: true })).toBeVisible();
  fixture.removalErrors.delete('removable-action');
  await dialog.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('heading', { name: 'Removable action', exact: true })).toHaveCount(0);
  expect(fixture.current.has('removable-action')).toBe(false);
  expect(fixture.versions.get('removable-action')?.get(entry.definition.sha256!)).toEqual(entry);
});

test('catalog and download failures remain visible and allow retry', async ({ page }) => {
  const fixture = await kameletFixture(page);
  fixture.seed('retry-action', { title: 'Retry action' });
  fixture.listErrors.push(503);
  await new KameletsPage(page).open();
  await expect(page.getByText('Could not load Kamelets', { exact: true })).toBeVisible();
  await expect(page.getByText('Kamelet catalog is unavailable', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Retry action', exact: true })).toBeVisible();
  fixture.downloadErrors.set('retry-action', 404);
  let downloads = 0;
  page.on('download', () => downloads++);
  await page.getByRole('button', { name: 'Download retry-action', exact: true }).click();
  await expect(page.getByText('Download failed', { exact: true })).toBeVisible();
  await expect(page.getByText('Kamelet revision is unavailable', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Download retry-action', exact: true })).toBeEnabled();
  expect(downloads).toBe(0);
});

test('new uploaded semantic actions appear when the wizard reopens without a page reload', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const candidate = fixture.define('remote-support-action', { title: 'Remote support', eligible: true });
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity();
  await expect(page.getByLabel('Remote support', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const documentStarted = await page.evaluate(() => performance.timeOrigin);
  const catalog = new KameletsPage(page);
  await catalog.navigate();
  await catalog.upload(candidate.definition.yaml!);
  await page.getByRole('navigation', { name: 'Wanaku', exact: true }).getByRole('link', { name: 'Semantic Routers', exact: true }).click();
  await page.getByRole('button', { name: 'Create semantic router', exact: true }).click();
  await wizard.identity();
  await expect(page.getByLabel('Remote support', { exact: true })).toBeEnabled();
  expect(await page.evaluate(() => performance.timeOrigin)).toBe(documentStarted);
  expect(fixture.semantic.actionRequests.length).toBeGreaterThanOrEqual(2);
  expect(fixture.semantic.saves).toHaveLength(0);
});

test('uploaded native sinks expose destination configuration and retain their revision in the wizard', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const sink = fixture.define('kafka-sink', {
    title: 'Kafka destination', type: 'sink', eligible: true, dependencies: ['camel:kafka'],
    yaml: `apiVersion: camel.apache.org/v1
kind: Kamelet
metadata:
  name: kafka-sink
  labels:
    camel.apache.org/kamelet.type: sink
spec:
  definition:
    title: Kafka destination
    required: [topic, bootstrapServers]
    properties:
      topic:
        type: string
        title: Topic
      bootstrapServers:
        type: string
        title: Bootstrap servers
      password:
        type: string
        title: Password
        format: password
  dependencies: [camel:kafka]
  template:
    from:
      uri: kamelet:source
      steps:
        - to:
            uri: kafka:{{topic}}
            parameters:
              brokers: "{{bootstrapServers}}"
`,
    configurationSchema: { type: 'object', required: ['topic', 'bootstrapServers'], properties: {
      topic: { type: 'string', title: 'Topic' },
      bootstrapServers: { type: 'string', title: 'Bootstrap servers' },
      password: { type: 'string', title: 'Password', format: 'password', 'x-secret-reference': true },
    } },
  });
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity('Kafka-routing');
  await expect(page.getByLabel('Kafka destination', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const catalog = new KameletsPage(page);
  await catalog.navigate();
  await catalog.upload(sink.definition.yaml!, 'kafka-sink.kamelet.yaml');
  await page.getByRole('navigation', { name: 'Wanaku', exact: true }).getByRole('link', { name: 'Semantic Routers', exact: true }).click();
  await page.getByRole('button', { name: 'Create semantic router', exact: true }).click();
  await wizard.identity('Kafka-routing');
  await expect(page.getByText('Sink destination', { exact: true })).toBeVisible();
  await page.getByLabel('Billing support', { exact: true }).check({ force: true });
  await page.getByLabel('Kafka destination', { exact: true }).check({ force: true });
  await expect(page.getByLabel('Topic (required)', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('Bootstrap servers (required)', { exact: true })).toHaveValue('');
  await wizard.next();
  await expect(page.getByRole('alert').filter({ hasText: 'Correct these fields' })).toContainText('Topic is required');
  await expect(page.getByRole('alert').filter({ hasText: 'Correct these fields' })).toContainText('Bootstrap servers is required');
  await page.getByLabel('Topic (required)', { exact: true }).fill('support-requests');
  await page.getByLabel('Bootstrap servers (required)', { exact: true }).fill('kafka:9092');
  const password = page.getByLabel('Password', { exact: true });
  await expect(password).toHaveAttribute('placeholder', 'env:VARIABLE');
  await expect(password).toHaveAccessibleDescription('Use env:VARIABLE. Do not enter a credential value.');
  await password.fill('env:KAFKA_PASSWORD');
  await wizard.next();
  await expect(page.getByLabel('Configured expert', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  const saved = [...fixture.semantic.definitions.values()][0];
  expect(saved.actions).toEqual([
    expect.objectContaining({ actionId: 'wsr-billing-action', sha256: 'b'.repeat(64), configuration: { prefix: 'Support' } }),
    expect.objectContaining({ actionId: 'kafka-sink', sha256: sink.definition.sha256, configuration: {
      topic: 'support-requests', bootstrapServers: 'kafka:9092', password: 'env:KAFKA_PASSWORD',
    } }),
  ]);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await page.getByRole('button', { name: 'Edit Kafka-routing', exact: true }).click();
  await wizard.next();
  await expect(page.getByLabel('Kafka destination', { exact: true })).toBeChecked();
  await expect(page.getByLabel('Topic (required)', { exact: true })).toHaveValue('support-requests');
  await expect(page.getByLabel('Bootstrap servers (required)', { exact: true })).toHaveValue('kafka:9092');
  await expect(password).toHaveValue('env:KAFKA_PASSWORD');
  expect(fixture.semantic.actionRequests).toContain(saved.id);
});

test('retain an older selected schema after upload and upgrade only by an explicit action', async ({ page }) => {
  const fixture = await kameletFixture(page);
  const older = fixture.seed('remote-support-action', { title: 'Remote support', eligible: true, prefix: 'Earlier default', prefixTitle: 'Earlier response prefix' });
  const newer = fixture.define('remote-support-action', { title: 'Remote support', eligible: true, prefix: 'Current default', prefixTitle: 'Current response prefix' });
  const wizard = new SemanticRoutersPage(page);
  await wizard.open();
  await wizard.identity('Pinned-router');
  await page.getByLabel('Billing support', { exact: true }).check({ force: true });
  await page.getByLabel('Remote support', { exact: true }).check({ force: true });
  await page.getByLabel('Earlier response prefix (required)', { exact: true }).fill('Customized old value');
  await wizard.next();
  await wizard.decision();
  await page.getByRole('button', { name: 'Back', exact: true }).click();
  await page.getByLabel('Action label', { exact: true }).nth(1).fill('remote_choice');
  await page.getByLabel('When to select remote_choice', { exact: true }).fill('Preserve this business rule.');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  const id = [...fixture.semantic.definitions.keys()][0];
  expect(fixture.semantic.definitions.get(id)?.actions?.[1].sha256).toBe(older.definition.sha256);
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  const catalog = new KameletsPage(page);
  await catalog.navigate();
  await catalog.upload(newer.definition.yaml!);
  await page.getByRole('navigation', { name: 'Wanaku', exact: true }).getByRole('link', { name: 'Semantic Routers', exact: true }).click();
  await page.getByRole('button', { name: 'Edit Pinned-router', exact: true }).click();
  await wizard.next();
  await expect(page.getByText('Earlier revision', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Earlier response prefix (required)', { exact: true })).toHaveValue('Customized old value');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  expect(fixture.semantic.definitions.get(id)?.actions?.[1]).toMatchObject({
    sha256: older.definition.sha256, label: 'remote_choice', criteria: 'Preserve this business rule.', configuration: { prefix: 'Customized old value' },
  });
  expect(fixture.semantic.actionRequests).toContain(id);
  await expect(page.getByLabel('Current response prefix (required)', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: 'Use current revision of Remote support', exact: true }).click();
  await expect(page.getByText('Earlier revision', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Earlier response prefix (required)', { exact: true })).toHaveCount(0);
  await expect(page.getByLabel('Current response prefix (required)', { exact: true })).toHaveValue('Current default');
  await wizard.next();
  await expect(page.getByLabel('Action label', { exact: true }).nth(1)).toHaveValue('remote_choice');
  await expect(page.getByLabel('When to select remote_choice', { exact: true })).toHaveValue('Preserve this business rule.');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect(page.getByText('Draft saved', { exact: true })).toBeVisible();
  expect(fixture.semantic.definitions.get(id)?.actions?.[1]).toMatchObject({
    sha256: newer.definition.sha256, label: 'remote_choice', criteria: 'Preserve this business rule.', configuration: { prefix: 'Current default' },
  });
  expect(fixture.semantic.definitions.get(id)?.actions?.[1].configuration).toEqual({ prefix: 'Current default' });
  expect(fixture.semantic.publishRequests).toHaveLength(0);
});
