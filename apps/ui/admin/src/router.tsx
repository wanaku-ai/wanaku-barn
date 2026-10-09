import { createHashRouter } from "react-router-dom";
import App from "./App";
import { ErrorPage } from "./Pages/Error";
import { Links } from "./router/links.models";

export const router = createHashRouter([
  {
    path: Links.Home,
    element: <App />,
    errorElement: <ErrorPage />,
    children: [
      {
        index: true,
        lazy: async () => import("./Pages/DataStores"),
      },
      {
        path: Links.DataStores,
        lazy: async () => import("./Pages/DataStores"),
      },
      {
        path: Links.ServiceCatalog,
        lazy: async () => import("./Pages/ServiceCatalog"),
      },
      {
        path: Links.Kamelets,
        lazy: async () => import("./Pages/Kamelets"),
      },
      {
        path: Links.SemanticRouters,
        lazy: async () => import("./Pages/SemanticRouters"),
      },
      {
        path: Links.ChangeHistory,
        lazy: async () => import("./Pages/ChangeHistory"),
      },
    ],
  },
]);
