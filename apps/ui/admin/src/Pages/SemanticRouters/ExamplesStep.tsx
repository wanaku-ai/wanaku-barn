import {
  Accordion,
  AccordionItem,
  Button,
  CodeSnippet,
  InlineLoading,
  InlineNotification,
  Select,
  SelectItem,
  TextArea,
  Tile,
} from "@carbon/react";
import type {
  SemanticExample,
  SemanticPreview,
  SemanticRouterDefinition,
} from "../../models";

export type PreviewState =
  | { status: "loading" }
  | { status: "success"; data: SemanticPreview }
  | { status: "error"; error: string };

interface ExamplesStepProps {
  definition: SemanticRouterDefinition;
  results: Record<number, PreviewState>;
  busy: boolean;
  onChange: (examples: SemanticExample[]) => void;
  onPreview: (index: number) => void;
}

const exampleHelp =
  "Enter a request an agent might send. Use the expected label to check how the expert classifies it.";

export function ExamplesStep({
  definition,
  results,
  busy,
  onChange,
  onPreview,
}: ExamplesStepProps) {
  const examples = definition.examples ?? [];
  const update = (index: number, change: Partial<SemanticExample>) => {
    onChange(
      examples.map((example, i) =>
        i === index ? { ...example, ...change } : example,
      ),
    );
  };
  return (
    <div className="semantic-router-fields">
      <InlineNotification
        kind="info"
        title="Classification only"
        hideCloseButton
        subtitle="Previews save this draft and use its expert and decision rules. They do not execute any action."
      />
      {examples.length === 0 && (
        <p>
          Add examples to compare expected and actual decisions before
          publication.
        </p>
      )}
      {examples.map((example, index) => {
        const result = results[index];
        return (
          <Tile
            key={index}
            className="semantic-router-fields"
            aria-label={`Example ${index + 1}`}
          >
            <TextArea
              id={`example-${index}`}
              labelText={`Example ${index + 1} message`}
              value={example.message ?? ""}
              placeholder="I was charged twice for my subscription. Can you help?"
              helperText={exampleHelp}
              aria-description={exampleHelp}
              maxLength={8192}
              onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
                update(index, { message: event.target.value })
              }
            />
            <Select
              id={`expected-${index}`}
              labelText={`Example ${index + 1} expected label`}
              value={example.expectedLabel ?? ""}
              onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
                update(index, { expectedLabel: event.target.value })
              }
            >
              <SelectItem value="" text="Select expected label" />
              {(definition.actions ?? []).map((action) => (
                <SelectItem
                  key={action.actionId}
                  value={action.label ?? ""}
                  text={action.label ?? ""}
                />
              ))}
              <SelectItem value="no_match" text="no_match (no action)" />
            </Select>
            <div className="semantic-router-buttons">
              <Button
                kind="tertiary"
                size="sm"
                disabled={
                  busy || !example.message?.trim() || !example.expectedLabel
                }
                onClick={() => onPreview(index)}
              >
                Preview example {index + 1}
              </Button>
              <Button
                kind="danger--ghost"
                size="sm"
                disabled={busy}
                onClick={() =>
                  onChange(examples.filter((_item, i) => i !== index))
                }
              >
                Remove example {index + 1}
              </Button>
            </div>
            <div aria-live="polite">
              {result?.status === "loading" && (
                <InlineLoading description="Evaluating example" />
              )}
              {result?.status === "error" && (
                <InlineNotification
                  kind="error"
                  title="Preview failed"
                  subtitle={result.error}
                  hideCloseButton
                />
              )}
              {result?.status === "success" && (
                <>
                  {result.data.error ? (
                    <InlineNotification
                      kind="error"
                      title="Evaluation error"
                      subtitle={result.data.error}
                      hideCloseButton
                    />
                  ) : (
                    <InlineNotification
                      kind={
                        (result.data.noMatch
                          ? "no_match"
                          : result.data.label) === example.expectedLabel
                          ? "success"
                          : "warning"
                      }
                      title={
                        result.data.noMatch
                          ? "No action matched"
                          : "Action selected"
                      }
                      hideCloseButton
                      subtitle={`Expected: ${example.expectedLabel}. Actual: ${result.data.noMatch ? "no_match" : result.data.label}.`}
                    />
                  )}
                  {result.data.durationMillis !== undefined && (
                    <p>Evaluation time: {result.data.durationMillis} ms.</p>
                  )}
                  {result.data.diagnostics &&
                    Object.keys(result.data.diagnostics).length > 0 && (
                      <Accordion>
                        <AccordionItem title="Available evaluation diagnostics">
                          <CodeSnippet type="multi" feedback="Copied">
                            {JSON.stringify(result.data.diagnostics, null, 2)}
                          </CodeSnippet>
                        </AccordionItem>
                      </Accordion>
                    )}
                </>
              )}
            </div>
          </Tile>
        );
      })}
      <Button
        kind="tertiary"
        disabled={busy || examples.length >= 30}
        onClick={() =>
          onChange([...examples, { message: "", expectedLabel: "" }])
        }
      >
        Add example
      </Button>
    </div>
  );
}
