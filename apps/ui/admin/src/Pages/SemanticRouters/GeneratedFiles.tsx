import { useEffect, useRef, useState } from "react";
import {
  Button,
  CodeSnippet,
  InlineLoading,
  InlineNotification,
  Select,
  SelectItem,
} from "@carbon/react";
import { View } from "@carbon/react/icons";
import type { SemanticRouterDefinition } from "../../models";
import { getErrorMessage } from "../../utils/error";
import { semanticRouterApi } from "./api";

interface GeneratedFilesProps {
  definition: SemanticRouterDefinition;
}

type FilesState =
  | { status: "closed" }
  | { status: "loading"; definition: SemanticRouterDefinition }
  | { status: "error"; definition: SemanticRouterDefinition; error: string }
  | {
      status: "success";
      definition: SemanticRouterDefinition;
      files: Record<string, string>;
      selected: string;
    };

export function GeneratedFiles({ definition }: GeneratedFilesProps) {
  const [state, setState] = useState<FilesState>({ status: "closed" });
  const request = useRef(0);
  const controller = useRef<AbortController | null>(null);
  const opened = state.status !== "closed" && state.definition === definition;

  useEffect(() => {
    return () => {
      // The plugin host may finish an aborted request. Ignore its stale response.
      controller.current?.abort();
      request.current += 1;
    };
  }, [definition]);

  const load = async () => {
    controller.current?.abort();
    const pending = new AbortController();
    controller.current = pending;
    const currentRequest = ++request.current;
    setState({ status: "loading", definition });
    try {
      const files = await semanticRouterApi.files(definition, pending.signal);
      if (pending.signal.aborted || currentRequest !== request.current) return;
      const names = Object.keys(files).sort();
      setState({
        status: "success",
        definition,
        files,
        selected: names.includes("service/router.camel.yaml")
          ? "service/router.camel.yaml"
          : (names[0] ?? ""),
      });
    } catch (cause) {
      if (pending.signal.aborted || currentRequest !== request.current) return;
      setState({ status: "error", definition, error: getErrorMessage(cause) });
    }
  };

  return (
    <div className="semantic-router-fields">
      <Button
        kind="ghost"
        size="sm"
        renderIcon={View}
        aria-expanded={opened}
        aria-controls="semantic-router-generated-files"
        onClick={() => {
          if (opened) {
            controller.current?.abort();
            request.current += 1;
            setState({ status: "closed" });
          } else {
            void load();
          }
        }}
      >
        View generated files
      </Button>
      <section
        id="semantic-router-generated-files"
        aria-label="Generated draft files"
        className="semantic-router-generated-files"
        hidden={!opened}
      >
        {opened && (
          <>
            <h3>Generated draft files</h3>
            <p>
              Draft preview. Publication assigns the final revision and catalog
              metadata. Viewing files does not save or publish this draft.
            </p>
            <div aria-live="polite">
              {state.status === "loading" && (
                <InlineLoading description="Generating draft files" />
              )}
              {state.status === "error" && (
                <>
                  <InlineNotification
                    kind="error"
                    title="Cannot generate files"
                    subtitle={state.error}
                    hideCloseButton
                  />
                  <Button kind="tertiary" size="sm" onClick={() => void load()}>
                    Retry generated files
                  </Button>
                </>
              )}
            </div>
            {state.status === "success" &&
              (state.selected ? (
                <div className="semantic-router-fields">
                  <Select
                    id="semantic-router-generated-file"
                    labelText="Generated file"
                    helperText="Select a file to view its generated content. These files are read-only."
                    value={state.selected}
                    onChange={(event: React.ChangeEvent<HTMLSelectElement>) => {
                      const selected = event.target.value;
                      setState((current) =>
                        current.status === "success"
                          ? { ...current, selected }
                          : current,
                      );
                    }}
                  >
                    {Object.keys(state.files)
                      .sort()
                      .map((name) => (
                        <SelectItem key={name} value={name} text={name} />
                      ))}
                  </Select>
                  <CodeSnippet type="multi" feedback="Copied">
                    {state.files[state.selected]}
                  </CodeSnippet>
                </div>
              ) : (
                <p>No generated files are available for this draft.</p>
              ))}
          </>
        )}
      </section>
    </div>
  );
}
