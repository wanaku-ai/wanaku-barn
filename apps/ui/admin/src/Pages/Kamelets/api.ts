import {
  deleteApiV1KameletsName,
  getApiV1Kamelets,
  getApiV1KameletsName,
  postApiV1Kamelets,
} from "../../api/wanaku-router-api";
import type { KameletDefinition, KameletSummary } from "../../models";

function payload<T>(response: { data: { data?: T } | void }): T {
  if (
    !response.data ||
    response.data.data === undefined ||
    response.data.data === null
  )
    throw new Error("Barn returned an empty response.");
  return response.data.data;
}

export const kameletApi = {
  list: async () => payload<KameletSummary[]>(await getApiV1Kamelets()),
  upload: async (yaml: string) =>
    payload<KameletSummary>(await postApiV1Kamelets({ yaml })),
  get: async (name: string, sha256?: string) =>
    payload<KameletDefinition>(await getApiV1KameletsName(name, { sha256 })),
  remove: async (name: string) => {
    await deleteApiV1KameletsName(name);
  },
};
