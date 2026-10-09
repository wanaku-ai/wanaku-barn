import { useCallback, useEffect, useRef, useState } from "react";
import {
  Button,
  Grid,
  Column,
  InlineNotification,
  Modal,
  SkeletonText,
  TableToolbar,
  TableToolbarContent,
  Tile,
} from "@carbon/react";
import type {
  SemanticAction,
  SemanticExpert,
  SemanticRouterDefinition,
} from "../../models";
import { getErrorMessage } from "../../utils/error";
import { semanticRouterApi } from "./api";
import { SemanticRouterWizard } from "./SemanticRouterWizard";
import { Link } from "react-router-dom";
import "./SemanticRouters.scss";

type CatalogState =
  | { status: "loading" }
  | { status: "error"; error: string }
  | {
      status: "success";
      definitions: SemanticRouterDefinition[];
      actions: SemanticAction[];
      experts: SemanticExpert[];
    };

interface WizardCatalog {
  initial: SemanticRouterDefinition | null;
  actions: SemanticAction[];
  experts: SemanticExpert[];
}

function useWizardCatalog(onError: (error: string | null) => void) {
  const [wizard, setWizard] = useState<WizardCatalog | null>(null);
  const [loadingWizard, setLoadingWizard] = useState(false);
  const wizardRequest = useRef(0);
  useEffect(
    () => () => {
      wizardRequest.current += 1;
    },
    [],
  );
  const openWizard = async (initial: SemanticRouterDefinition | null) => {
    const request = ++wizardRequest.current;
    setLoadingWizard(true);
    onError(null);
    try {
      const [actions, experts] = await Promise.all([
        semanticRouterApi.actions(initial?.id),
        semanticRouterApi.experts(),
      ]);
      if (request === wizardRequest.current)
        setWizard({ initial, actions, experts });
    } catch (cause) {
      if (request === wizardRequest.current) onError(getErrorMessage(cause));
    } finally {
      if (request === wizardRequest.current) setLoadingWizard(false);
    }
  };

  return { wizard, setWizard, loadingWizard, openWizard };
}

export function SemanticRoutersPage() {
  const [catalog, setCatalog] = useState<CatalogState>({ status: "loading" });
  const [removing, setRemoving] = useState<SemanticRouterDefinition | null>(
    null,
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const { wizard, setWizard, loadingWizard, openWizard } =
    useWizardCatalog(setError);
  const load = useCallback(async () => {
    try {
      const [definitions, actions, experts] = await Promise.all([
        semanticRouterApi.list(),
        semanticRouterApi.actions(),
        semanticRouterApi.experts(),
      ]);
      setCatalog({ status: "success", definitions, actions, experts });
    } catch (cause) {
      setCatalog({ status: "error", error: getErrorMessage(cause) });
    }
  }, []);
  useEffect(() => {
    void load();
  }, [load]);

  const remove = async () => {
    if (!removing?.id) return;
    setBusy(true);
    setError(null);
    try {
      await semanticRouterApi.remove(removing.id);
      setRemoving(null);
      await load();
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="semantic-routers-page">
      <div className="semantic-router-header">
        <h1 className="title">Semantic Routers</h1>
        <p className="description">
          Semantic routes replace rigid keyword rules with natural language to
          make intelligent routing decisions. By configuring AI-driven
          "questions"—such as asking "Which department should handle this?" or
          "Is this message actionable?"—the system evaluates incoming data and
          returns structured answers like true/false decisions, categories, or
          scores. These semantic answers can instantly drive dynamic message
          filtering, classification, and routing in your pipeline, allowing you
          to build intelligent automation without writing complex code.{" "}
          <Link to="/change-history?type=semantic_router">View the change history</Link>
        </p>
      </div>
      <div id="page-content">
        <div className="semantic-router-content">
          {error && (
            <InlineNotification
              kind="error"
              title="Request failed"
              subtitle={error}
              hideCloseButton
            />
          )}
          {catalog.status === "loading" && (
            <SkeletonText paragraph lineCount={4} />
          )}
          {catalog.status === "error" && (
            <div className="semantic-router-fields">
              <InlineNotification
                kind="error"
                title="Could not load semantic routers"
                subtitle={catalog.error}
                hideCloseButton
              />
              <Button kind="tertiary" onClick={load}>
                Retry
              </Button>
            </div>
          )}
          {catalog.status === "success" && (
            <>
              <TableToolbar className="semantic-router-toolbar">
                <TableToolbarContent>
                  <Button
                    disabled={loadingWizard}
                    onClick={() => void openWizard(null)}
                  >
                    Create semantic router
                  </Button>
                </TableToolbarContent>
              </TableToolbar>
              {loadingWizard && (
                <p role="status">Loading the latest action catalog…</p>
              )}
              <Grid fullWidth className="semantic-router-list">
                {catalog.definitions.length === 0 && (
                  <Column sm={4} md={8} lg={16}>
                    <Tile>
                      <p>
                        No semantic routers yet. Create a router to start with a
                        curated set of actions.
                      </p>
                    </Tile>
                  </Column>
                )}
                {catalog.definitions.map((definition) => (
                  <Column
                    sm={4}
                    md={4}
                    lg={4}
                    key={definition.id}
                    className="semantic-router-column"
                  >
                    <Tile className="semantic-router-card">
                      <h2>{definition.name || "Untitled draft"}</h2>
                      <p>
                        {definition.description ||
                          "No business purpose supplied."}
                      </p>
                      <p>
                        Editable definition. Open review to inspect published
                        revisions.
                      </p>
                      <div className="semantic-router-buttons">
                        <Button
                          kind="tertiary"
                          size="sm"
                          aria-label={`Edit ${definition.name || "draft"}`}
                          disabled={loadingWizard}
                          onClick={() => void openWizard(definition)}
                        >
                          Edit
                        </Button>
                        <Button
                          kind="danger--ghost"
                          size="sm"
                          aria-label={`Remove ${definition.name || "draft"}`}
                          onClick={() => {
                            setError(null);
                            setRemoving(definition);
                          }}
                        >
                          Remove
                        </Button>
                      </div>
                    </Tile>
                  </Column>
                ))}
              </Grid>
              {wizard && (
                <SemanticRouterWizard
                  initial={wizard.initial}
                  actions={wizard.actions}
                  experts={wizard.experts}
                  onClose={() => setWizard(null)}
                  onSaved={() => {
                    void load();
                  }}
                />
              )}
            </>
          )}
        </div>
      </div>
      <Modal
        open={Boolean(removing)}
        danger
        modalHeading="Remove semantic router?"
        primaryButtonText="Remove"
        secondaryButtonText="Cancel"
        primaryButtonDisabled={busy}
        onRequestSubmit={remove}
        onRequestClose={() => {
          if (!busy) setRemoving(null);
        }}
      >
        <p>
          Remove the editable definition for {removing?.name || "this draft"}?
          Existing WSR deployments do not change.
        </p>
        {error && (
          <InlineNotification
            kind="error"
            title="Removal failed"
            subtitle={error}
            hideCloseButton
          />
        )}
      </Modal>
    </div>
  );
}
