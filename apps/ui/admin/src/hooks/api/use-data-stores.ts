import {useCallback} from "react";
import {
  deleteApiV1DataStoreId,
  getApiV1DataStore,
  getApiV1DataStoreId,
  postApiV1DataStore,
  putApiV1DataStore
} from "../../api/wanaku-router-api";
import type {
  DataStore,
  DataStoreRecord,
  GetApiV1DataStoreParams,
} from "../../models";

/**
 * Custom hook for DataStore API operations
 */
export const useDataStores = () => {
  const getDataStore = useCallback(
    (id: string, options?: RequestInit) => getApiV1DataStoreId(id, options),
    []
  );
  const listDataStores = useCallback(
    (params?: GetApiV1DataStoreParams, options?: RequestInit) => {
      return getApiV1DataStore(params, options);
    },
    []
  );

  const addDataStore = useCallback(
    (dataStore: DataStore, options?: RequestInit) => {
      return postApiV1DataStore(dataStore, options);
    },
    []
  );

  // Sends the revision that was read, so the server rejects the update with 409 if the entry changed.
  const updateDataStore = useCallback(
    (dataStore: DataStore | DataStoreRecord, options?: RequestInit) => {
      const revision = (dataStore as DataStoreRecord).revision;
      const params = revision ? { expectedRevision: revision } : undefined;
      return putApiV1DataStore(dataStore, params, options);
    },
    []
  );

  const deleteDataStore = useCallback(
    (id: string, expectedRevision?: number, options?: RequestInit) => {
      const params = expectedRevision ? { expectedRevision } : undefined;
      return deleteApiV1DataStoreId(id, params, options);
    },
    []
  );

  return {
    getDataStore,
    listDataStores,
    addDataStore,
    updateDataStore,
    deleteDataStore,
  };
};
