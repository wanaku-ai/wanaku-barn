import {
  deleteApiV1SemanticRoutersId,
  getApiV1SemanticRouters,
  getApiV1SemanticRoutersActions,
  getApiV1SemanticRoutersExperts,
  getApiV1SemanticRoutersIdRevisions,
  getApiV1SemanticRoutersResolve,
  postApiV1SemanticRouters,
  postApiV1SemanticRoutersFiles,
  postApiV1SemanticRoutersIdPreview,
  postApiV1SemanticRoutersIdPublish,
  postApiV1SemanticRoutersValidate,
  putApiV1SemanticRoutersId,
} from "../../api/wanaku-router-api";
import type {
  SemanticAction,
  SemanticExpert,
  SemanticPreview,
  SemanticPublication,
  SemanticRouterDefinition,
  SemanticResolvedPublication,
  SemanticValidation,
} from "../../models";

function payload<T>(response: { data: { data?: T } | void }): T {
  if (
    !response.data ||
    response.data.data === undefined ||
    response.data.data === null
  )
    throw new Error("Barn returned an empty response.");
  return response.data.data;
}

/** Generated clients use the same Barn response envelope in standalone and plugin modes. */
export const semanticRouterApi = {
  list: async () =>
    payload<SemanticRouterDefinition[]>(await getApiV1SemanticRouters()),
  actions: async (definitionId?: string) =>
    payload<SemanticAction[]>(
      await getApiV1SemanticRoutersActions({ definitionId }),
    ),
  experts: async () =>
    payload<SemanticExpert[]>(await getApiV1SemanticRoutersExperts()),
  save: async (definition: SemanticRouterDefinition) =>
    payload<SemanticRouterDefinition>(
      definition.id
        ? await putApiV1SemanticRoutersId(definition.id, definition)
        : await postApiV1SemanticRouters(definition),
    ),
  validate: async (definition: SemanticRouterDefinition) =>
    payload<SemanticValidation>(
      await postApiV1SemanticRoutersValidate(definition),
    ),
  files: async (definition: SemanticRouterDefinition, signal: AbortSignal) =>
    payload<Record<string, string>>(
      await postApiV1SemanticRoutersFiles(definition, { signal }),
    ),
  preview: async (id: string, message: string) =>
    payload<SemanticPreview>(
      await postApiV1SemanticRoutersIdPreview(id, { message }),
    ),
  publish: async (id: string) =>
    payload<SemanticPublication>(await postApiV1SemanticRoutersIdPublish(id)),
  resolve: async (name: string) =>
    payload<SemanticResolvedPublication>(
      await getApiV1SemanticRoutersResolve({ name }),
    ),
  revisions: async (id: string) =>
    payload<SemanticPublication[]>(
      await getApiV1SemanticRoutersIdRevisions(id),
    ),
  remove: async (id: string) => {
    await deleteApiV1SemanticRoutersId(id);
  },
};
