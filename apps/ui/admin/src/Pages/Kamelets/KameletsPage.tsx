import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  InlineLoading,
  InlineNotification,
  SkeletonText,
  TableToolbar,
  TableToolbarContent,
  TableToolbarSearch,
} from "@carbon/react";
import { Add, Renew } from "@carbon/react/icons";
import type { KameletSummary } from "../../models";
import { getErrorMessage } from "../../utils/error";
import { kameletApi } from "./api";
import { KameletCards } from "./KameletCards";
import { UploadKameletModal } from "./UploadKameletModal";
import { RemoveKameletModal } from "./RemoveKameletModal";
import "./Kamelets.scss";

type CatalogState =
  | { status: "loading" }
  | { status: "error"; error: string }
  | { status: "success"; kamelets: KameletSummary[] };

export function KameletsPage() {
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading" });
  const [search, setSearch] = useState("");
  const [uploading, setUploading] = useState(false);
  const [removing, setRemoving] = useState<KameletSummary | null>(null);
  const [downloading, setDownloading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const request = useRef(0);
  const mounted = useRef(true);
  const load = useCallback(async () => {
    if (!mounted.current) return;
    const current = ++request.current;
    setCatalog({ status: "loading" });
    try {
      const kamelets = await kameletApi.list();
      if (mounted.current && current === request.current)
        setCatalog({ status: "success", kamelets });
    } catch (cause) {
      if (mounted.current && current === request.current)
        setCatalog({ status: "error", error: getErrorMessage(cause) });
    }
  }, []);
  useEffect(() => {
    mounted.current = true;
    void load();
    return () => {
      mounted.current = false;
      request.current += 1;
    };
  }, [load]);

  const download = async (kamelet: KameletSummary) => {
    if (!kamelet.name) return;
    setDownloading(kamelet.name);
    setError(null);
    try {
      const definition = await kameletApi.get(kamelet.name, kamelet.sha256);
      if (!mounted.current) return;
      if (definition.yaml === undefined)
        throw new Error("Barn returned no YAML for this Kamelet.");
      const blob = new Blob([definition.yaml], {
        type: "application/yaml;charset=utf-8",
      });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = `${kamelet.name}.kamelet.yaml`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      if (mounted.current) setError(getErrorMessage(cause));
    } finally {
      if (mounted.current) setDownloading(null);
    }
  };
  const query = search.trim().toLowerCase();
  const visible =
    catalog.status === "success"
      ? catalog.kamelets.filter((kamelet) =>
          `${kamelet.name ?? ""} ${kamelet.title ?? ""} ${kamelet.description ?? ""} ${kamelet.type ?? ""}`
            .toLowerCase()
            .includes(query),
        )
      : [];

  return (
    <div className="kamelets-page">
      <div className="kamelets-header">
        <h1 className="title">Kamelets</h1>
        <p className="description">
          Manage reusable Camel actions, sources, and sinks. Upload Kamelets to
          this catalog and download their YAML. Eligible semantic actions are
          available to new router configurations without a server restart.
        </p>
      </div>
      <div id="page-content">
        <div className="kamelets-content">
          <TableToolbar>
            <TableToolbarContent>
              <TableToolbarSearch
                labelText="Search Kamelets"
                placeholder="Search Kamelets"
                persistent
                value={search}
                onChange={(_event, value) => setSearch(value ?? "")}
              />
              <Button
                kind="ghost"
                renderIcon={Renew}
                hasIconOnly
                iconDescription="Refresh Kamelets"
                disabled={catalog.status === "loading"}
                onClick={() => void load()}
              />
              <Button renderIcon={Add} onClick={() => setUploading(true)}>
                Upload Kamelet
              </Button>
            </TableToolbarContent>
          </TableToolbar>
          {error && (
            <InlineNotification
              kind="error"
              title="Download failed"
              subtitle={error}
              hideCloseButton
            />
          )}
          {downloading && (
            <InlineLoading description="Preparing Kamelet download" />
          )}
          {catalog.status === "loading" && (
            <SkeletonText paragraph lineCount={4} />
          )}
          {catalog.status === "error" && (
            <div className="kamelet-upload-fields">
              <InlineNotification
                kind="error"
                title="Could not load Kamelets"
                subtitle={catalog.error}
                hideCloseButton
              />
              <Button kind="tertiary" onClick={() => void load()}>
                Retry
              </Button>
            </div>
          )}
          {catalog.status === "success" && (
            <KameletCards
              kamelets={visible}
              downloading={downloading}
              filtered={Boolean(query)}
              onDownload={(kamelet) => void download(kamelet)}
              onRemove={setRemoving}
            />
          )}
        </div>
      </div>
      {uploading && (
        <UploadKameletModal
          onClose={() => setUploading(false)}
          onUploaded={() => {
            setUploading(false);
            void load();
          }}
        />
      )}
      {removing && (
        <RemoveKameletModal
          kamelet={removing}
          onClose={() => setRemoving(null)}
          onRemoved={() => {
            setRemoving(null);
            void load();
          }}
        />
      )}
    </div>
  );
}
