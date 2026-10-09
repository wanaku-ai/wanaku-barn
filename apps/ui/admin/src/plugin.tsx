import "./process-shim";
import { StrictMode } from "react";
import { createRoot, Root } from "react-dom/client";
import {
  setPluginHost,
  clearPluginHost,
  setBackendUrl,
  type PluginHost,
} from "./plugin-host";
import { DataStoresPage } from "./Pages/DataStores/DataStoresPage";
import { ServiceCatalogPage } from "./Pages/ServiceCatalog/ServiceCatalogPage";
import { SemanticRoutersPage } from "./Pages/SemanticRouters/SemanticRoutersPage";
import { KameletsPage } from "./Pages/Kamelets/KameletsPage";
import { ChangeHistoryPage } from "./Pages/ChangeHistory/ChangeHistoryPage";
import "./plugin-styles.scss";

interface Disposable {
  dispose(): void;
}

const disposables: Disposable[] = [];
const roots: Root[] = [];

function mountPage(container: HTMLElement, Page: React.FC): Disposable {
  container.classList.add("wanaku-barn-plugin");
  const root = createRoot(container);
  roots.push(root);
  root.render(
    <StrictMode>
      <Page />
    </StrictMode>,
  );
  return {
    dispose() {
      root.unmount();
      const idx = roots.indexOf(root);
      if (idx >= 0) roots.splice(idx, 1);
    },
  };
}

export async function activate(host: PluginHost) {
  setPluginHost(host);

  if (typeof VITE_BACKEND_URL === "string" && VITE_BACKEND_URL) {
    setBackendUrl(VITE_BACKEND_URL);
  }

  disposables.push(
    host.navigation.add({
      id: "wanaku-data-stores",
      label: "Data Stores",
      route: "/wanaku/data-stores",
      order: 100,
    }),
  );

  disposables.push(
    host.navigation.add({
      id: "wanaku-service-catalog",
      label: "Service Catalog",
      route: "/wanaku/service-catalog",
      order: 110,
    }),
  );

  disposables.push(
    host.pages.register({
      route: "/wanaku/data-stores",
      mount: (container) => mountPage(container, DataStoresPage),
    }),
  );

  disposables.push(
    host.pages.register({
      route: "/wanaku/service-catalog",
      mount: (container) => mountPage(container, ServiceCatalogPage),
    }),
  );

  disposables.push(
    host.navigation.add({
      id: "wanaku-semantic-routers",
      label: "Semantic Routers",
      route: "/wanaku/semantic-routers",
      order: 120,
    }),
  );
  disposables.push(
    host.pages.register({
      route: "/wanaku/semantic-routers",
      mount: (container) => mountPage(container, SemanticRoutersPage),
    }),
  );
  disposables.push(
    host.navigation.add({
      id: "wanaku-kamelets",
      label: "Kamelets",
      route: "/wanaku/kamelets",
      order: 130,
    }),
    host.pages.register({
      route: "/wanaku/kamelets",
      mount: (container) => mountPage(container, KameletsPage),
    }),
  );
  disposables.push(
    host.navigation.add({
      id: "wanaku-change-history",
      label: "Change History",
      route: "/wanaku/change-history",
      order: 140,
    }),
    host.pages.register({
      route: "/wanaku/change-history",
      mount: (container) => mountPage(container, ChangeHistoryPage),
    }),
  );
}

export function deactivate() {
  for (const d of disposables) {
    d.dispose();
  }
  disposables.length = 0;

  for (const root of roots) {
    root.unmount();
  }
  roots.length = 0;

  clearPluginHost();
}
