import React, {useEffect, useState} from "react";
import {InlineLoading, InlineNotification} from "@carbon/react";
import {AddDataStoreModal} from "./AddDataStoreModal";
import {ViewDataStoreModal} from "./ViewDataStoreModal";
import {DataStoresTable} from "./DataStoresTable";
import {useDataStores} from "../../hooks/api/use-data-stores";
import type {DataStore} from "../../models";

function prepareDownload(stored: DataStore | undefined, fallbackName?: string): { blob: Blob; filename: string } {
  if (!stored?.data) throw new Error("No data is available for this file.");
  const filename = stored.name || fallbackName || "download";
  const type = stored.labels?.["wanaku.type"];
  if (type === "semantic-definition" || type === "semantic-publication" || type === "semantic-current-publication") {
    // Semantic route records are stored as plain JSON.
    return {
      blob: new Blob([stored.data], { type: "application/json;charset=utf-8" }),
      filename: filename.endsWith(".json") ? filename : `${filename}.json`,
    };
  }
  const isSemanticCatalog = type === "catalog" && stored.labels?.["semantic.immutable"] === "true";
  const bytes = Uint8Array.from(atob(stored.data), (character) => character.charCodeAt(0));
  return {
    blob: new Blob([bytes], { type: isSemanticCatalog ? "application/zip" : "application/octet-stream" }),
    filename: isSemanticCatalog && !filename.endsWith(".zip") ? `${filename}.zip` : filename,
  };
}

export const DataStoresPage: React.FC = () => {
  const [fetchedData, setFetchedData] = useState<DataStore[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [viewDataStore, setViewDataStore] = useState<DataStore | null>(null);
  const [downloading, setDownloading] = useState(false);
  const { listDataStores, getDataStore, addDataStore, deleteDataStore } = useDataStores();

  // Fetch data on mount
  useEffect(() => {
    listDataStores()
      .then((result) => {
        setFetchedData((result.data.data as DataStore[]) || []);
        setIsLoading(false);
      })
      .catch(() => {
        setErrorMessage("Failed to load data stores");
        setIsLoading(false);
      });
  }, [listDataStores]);

  if (isLoading) {
    return <div>Loading...</div>;
  }

  const handleAddDataStore = async (newDataStore: DataStore) => {
    try {
      await addDataStore(newDataStore);
      setIsAddModalOpen(false);
      setErrorMessage(null);

      // Refresh the list
      listDataStores().then((result) => {
        setFetchedData((result.data.data as DataStore[]) || []);
      });
    } catch {
      setErrorMessage("Error adding data store. Please try again.");
    }
  };

  const handleDelete = async (id: string) => {
    try {
      await deleteDataStore(id);

      // Refresh the list
      listDataStores().then((result) => {
        setFetchedData((result.data.data as DataStore[]) || []);
      });
    } catch {
      setErrorMessage(`Failed to delete data store`);
    }
  };

  const handleDownload = async (dataStore: DataStore) => {
    setDownloading(true);
    setErrorMessage(null);
    try {
      const stored = dataStore.id ? (await getDataStore(dataStore.id)).data.data : dataStore;
      const { blob, filename } = prepareDownload(stored, dataStore.name);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
      // Let the browser consume the Blob before releasing its URL.
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      setErrorMessage(`Failed to download file. ${error instanceof Error ? error.message : "Please try again."}`);
    } finally {
      setDownloading(false);
    }
  };

  return (
    <div>
      {errorMessage && (
        <InlineNotification
          kind="error"
          title="Error"
          subtitle={errorMessage}
          onCloseButtonClick={() => setErrorMessage(null)}
        />
      )}
      <h1 className="title">Data Stores</h1>
      <p className="description">
        Manage stored data files. Download published semantic catalogs as ZIP files
        and semantic definitions or publication records as JSON files.
      </p>
      {downloading && <InlineLoading description="Preparing download…" />}
      <div id="page-content">
        <DataStoresTable
          dataStores={fetchedData}
          onDelete={handleDelete}
          onAdd={() => setIsAddModalOpen(true)}
          onDownload={handleDownload}
          onView={(dataStore) => setViewDataStore(dataStore)}
          downloading={downloading}
        />
      </div>
      {isAddModalOpen && (
        <AddDataStoreModal
          onRequestClose={() => setIsAddModalOpen(false)}
          onSubmit={handleAddDataStore}
        />
      )}
      {viewDataStore && (
        <ViewDataStoreModal
          dataStore={viewDataStore}
          onRequestClose={() => setViewDataStore(null)}
        />
      )}
    </div>
  );
};
