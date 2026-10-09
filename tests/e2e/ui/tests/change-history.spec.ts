import { test, expect, type Page } from '@playwright/test';
import type { AuditEvent } from '../../../../apps/ui/admin/src/models';
import { ChangeHistoryPage } from '../pages/change-history.page';

const events: AuditEvent[] = [
  { event_id: 'e3', operation: 'semantic_router.publish', target_type: 'semantic_router', target: 'support', decision: 'allow', reason_code: 'completed', response_status: 200, timestamp: '2026-10-09T10:02:00Z' },
  { event_id: 'e2', operation: 'service_catalog.deploy', target_type: 'service_catalog', target: 'billing', decision: 'reject_malformed', reason_code: 'conflict', response_status: 409, timestamp: '2026-10-09T10:01:00Z' },
  { event_id: 'e1', operation: 'service_catalog.deploy', target_type: 'service_catalog', target: 'billing', decision: 'allow', reason_code: 'completed', response_status: 200, timestamp: '2026-10-09T10:00:00Z' },
];

/** Serves the audit API from the events above and records the target types that the page requests. */
async function auditApi(page: Page) {
  const requested: (string | null)[] = [];
  await page.route('**/api/v1/audit/events**', async route => {
    const targetType = new URL(route.request().url()).searchParams.get('target_type');
    requested.push(targetType);
    const matching = events.filter(event => !targetType || event.target_type === targetType);
    await route.fulfill({ json: { data: { events: matching, offset: 0, limit: 25, total: matching.length } } });
  });
  return requested;
}

test('change history lists all events newest first', async ({ page }) => {
  await auditApi(page);
  const history = new ChangeHistoryPage(page);
  await history.open();
  await expect(history.rows()).toHaveCount(3);
  await expect(history.rows().first()).toContainText('semantic_router.publish');
  await expect(history.rows().nth(1)).toContainText('conflict');
});

test('filter the change history to service catalogs', async ({ page }) => {
  const requested = await auditApi(page);
  const history = new ChangeHistoryPage(page);
  await history.open();
  await history.resource().selectOption('service_catalog');
  await expect(history.rows()).toHaveCount(2);
  await expect(history.rows().first()).toContainText('billing');
  expect(requested).toContain('service_catalog');
});

test('open the semantic router change history from a link', async ({ page }) => {
  const requested = await auditApi(page);
  const history = new ChangeHistoryPage(page);
  await history.open('?type=semantic_router');
  await expect(history.resource()).toHaveValue('semantic_router');
  await expect(history.rows()).toHaveCount(1);
  await expect(history.rows().first()).toContainText('support');
  expect(requested).toEqual(['semantic_router']);
});

test('show an empty state when no events match', async ({ page }) => {
  await page.route('**/api/v1/audit/events**', route =>
    route.fulfill({ json: { data: { events: [], offset: 0, limit: 25, total: 0 } } }));
  const history = new ChangeHistoryPage(page);
  await history.open('?type=kamelet');
  await expect(page.getByText('No changes found.')).toBeVisible();
});
