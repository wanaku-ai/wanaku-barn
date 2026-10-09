import type { Page } from '@playwright/test';
import { createHash } from 'node:crypto';
import type { KameletDefinition, SemanticAction } from '../../../../apps/ui/admin/src/models';
import { semanticRouterFixture } from './semantic-router-fixture';

interface FixtureOptions {
  title?: string;
  description?: string;
  type?: 'source' | 'sink' | 'action';
  eligible?: boolean;
  prefix?: string;
  prefixTitle?: string;
  source?: 'bundled' | 'configured' | 'uploaded';
  yaml?: string;
  configurationSchema?: SemanticAction['configurationSchema'];
  dependencies?: string[];
}

type Revision = { definition: KameletDefinition; action?: SemanticAction };

/** HTTP fixtures keep exact bytes and selected revisions; native YAML validation belongs to backend tests. */
export async function kameletFixture(page: Page) {
  const current = new Map<string, Revision>();
  const versions = new Map<string, Map<string, Revision>>();
  const accepted = new Map<string, Revision>();
  const uploads: string[] = [], removals: string[] = [];
  const downloads: { name: string; sha256?: string }[] = [];
  const uploadErrors: { status: number; message: string }[] = [];
  const removalErrors = new Map<string, number>();
  const downloadErrors = new Map<string, number>();
  const listErrors: number[] = [];

  const define = (name: string, options: FixtureOptions = {}): Revision => {
    const title = options.title ?? name, type = options.type ?? 'action';
    const eligible = type === 'source' ? false : options.eligible ?? false;
    const prefix = options.prefix ?? 'Support';
    const prefixTitle = options.prefixTitle ?? 'Remote response prefix';
    const description = options.description ?? 'Handle requests for café customers';
    const yaml = options.yaml ?? `# Exact UTF-8 café — unchanged on download\r\napiVersion: camel.apache.org/v1\r\nkind: Kamelet\r\nmetadata:\r\n  name: ${name}\r\n  labels:\r\n    camel.apache.org/kamelet.type: ${type}\r\n${eligible ? '  annotations:\r\n    barn.wanaku.ai/semantic-action: "true"\r\n    barn.wanaku.ai/contract-profile: message-to-string/v1\r\n    barn.wanaku.ai/routing-description: Escalated account requests\r\n' : ''}spec:\r\n  definition:\r\n    title: ${JSON.stringify(title)}\r\n    description: ${JSON.stringify(description)}\r\n    required: [prefix]\r\n    properties:\r\n      prefix:\r\n        type: string\r\n        title: ${JSON.stringify(prefixTitle)}\r\n        default: ${JSON.stringify(prefix)}\r\n        minLength: 1\r\n  types:\r\n    in:\r\n      schema:\r\n        type: string\r\n    out:\r\n      schema:\r\n        type: string\r\n  dependencies: [camel:core]\r\n  template:\r\n    from:\r\n      uri: kamelet:source\r\n      steps:\r\n        - setBody:\r\n            simple: "{{prefix}}"\r\n`;
    const sha256 = createHash('sha256').update(yaml, 'utf8').digest('hex');
    const definition: KameletDefinition = {
      name, title, type, sha256, yaml, description,
      semanticEligible: eligible, semanticEligibilityReason: eligible ? null : type === 'action' ? 'Add barn.wanaku.ai/semantic-action: true' : 'Only action Kamelets can be used for semantic routing',
      source: options.source ?? 'uploaded', removable: !options.source || options.source === 'uploaded',
      downloadUrl: `/api/v1/kamelets/${name}.kamelet.yaml?sha256=${sha256}`,
    };
    const action: SemanticAction | undefined = type !== 'source' && eligible ? {
      id: name, name: title, type, description: definition.description, sha256, current: true,
      profile: 'message-to-string/v1', criteria: 'Escalated account requests',
      dependencies: options.dependencies ?? ['camel:core'], inputSchema: { type: 'string' }, outputSchema: { type: 'string' },
      configurationSchema: options.configurationSchema ?? { type: 'object', required: ['prefix'], properties: {
        prefix: { type: 'string', title: prefixTitle, default: prefix, minLength: 1 },
      } },
    } : undefined;
    const revision = { definition, action };
    accepted.set(yaml, revision);
    return revision;
  };
  const store = (revision: Revision) => {
    const { name, sha256 } = revision.definition;
    const history = versions.get(name!) ?? new Map<string, Revision>();
    history.set(sha256!, revision);
    versions.set(name!, history);
    current.set(name!, revision);
  };
  const seed = (name: string, options: FixtureOptions = {}) => {
    const revision = define(name, options);
    store(revision);
    return revision;
  };
  const semantic = await semanticRouterFixture(page, (defaults, definitionId) => {
    const actions = [...defaults, ...[...current.values()].flatMap(revision => revision.action ? [revision.action] : [])];
    for (const selected of semantic.definitions.get(definitionId ?? '')?.actions ?? []) {
      if (!selected.actionId || !selected.sha256) continue;
      if (current.get(selected.actionId)?.definition.sha256 === selected.sha256) continue;
      const historical = versions.get(selected.actionId)?.get(selected.sha256)?.action;
      if (historical) actions.push({ ...historical, current: false });
    }
    return actions;
  });
  await page.route('**/api/v1/kamelets**', async route => {
    const request = route.request(), url = new URL(request.url());
    const path = url.pathname.replace('/api/v1/kamelets', '');
    const fail = (status: number, message: string) => route.fulfill({ status, json: { error: { message } } });
    if (!path || path === '/') {
      if (request.method() === 'GET') {
        const status = listErrors.shift();
        if (status) return fail(status, 'Kamelet catalog is unavailable');
        return route.fulfill({ json: { data: [...current.values()].map(({ definition: { yaml, ...summary } }) => summary) } });
      }
      const yaml = (request.postDataJSON() as { yaml: string }).yaml;
      uploads.push(yaml);
      const failure = uploadErrors.shift();
      if (failure) return fail(failure.status, failure.message);
      const revision = accepted.get(yaml);
      if (!revision) return fail(422, 'Use a single native Kamelet YAML document');
      store(revision);
      const { yaml: original, ...summary } = revision.definition;
      return route.fulfill({ json: { data: summary } });
    }
    const name = decodeURIComponent(path.substring(1)).replace(/\.kamelet\.yaml$/, '');
    if (request.method() === 'DELETE') {
      removals.push(name);
      const status = removalErrors.get(name);
      if (status) return fail(status, 'Kamelet removal is unavailable');
      current.delete(name);
      return route.fulfill({ json: { data: null } });
    }
    const sha256 = url.searchParams.get('sha256') ?? undefined;
    downloads.push({ name, sha256 });
    const status = downloadErrors.get(name);
    if (status) return fail(status, 'Kamelet revision is unavailable');
    const revision = sha256 ? versions.get(name)?.get(sha256) : current.get(name);
    if (!revision) return fail(404, 'Kamelet revision is unavailable');
    if (path.endsWith('.kamelet.yaml')) return route.fulfill({ contentType: 'application/yaml;charset=utf-8', body: revision.definition.yaml! });
    return route.fulfill({ json: { data: revision.definition } });
  });
  return { current, versions, uploads, removals, downloads, uploadErrors, removalErrors, downloadErrors, listErrors, define, seed, semantic };
}
