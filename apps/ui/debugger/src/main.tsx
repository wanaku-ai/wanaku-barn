import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { DebuggerPage } from "./DebuggerPage";
import "@fontsource-variable/inter";
import "./styles.scss";
const container = document.getElementById("root");
if (container)
  createRoot(container).render(
    <StrictMode>
      <DebuggerPage />
    </StrictMode>,
  );
