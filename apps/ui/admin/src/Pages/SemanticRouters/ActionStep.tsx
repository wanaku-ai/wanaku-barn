import { Button, Tag, Checkbox, Tile } from "@carbon/react";
import type {
  SemanticAction,
  SemanticActionSelection,
  SemanticFieldError,
} from "../../models";
import { configurationFields } from "./schema";
import { ActionConfigurationFields } from "./ActionConfigurationFields";

interface ActionStepProps {
  available: SemanticAction[];
  selections: SemanticActionSelection[];
  profile: string;
  errors: SemanticFieldError[];
  onChange: (selections: SemanticActionSelection[]) => void;
}

function defaultConfiguration(action: SemanticAction): Record<string, string> {
  return Object.fromEntries(
    configurationFields(action)
      .filter((field) => field.defaultValue !== "")
      .map((field) => [field.name, field.defaultValue]),
  );
}

export function ActionStep({
  available,
  selections,
  profile,
  errors,
  onChange,
}: ActionStepProps) {
  const update = (
    index: number,
    name: string,
    value: string,
    required: boolean,
  ) => {
    const configuration = { ...selections[index].configuration };
    if (value === "" && !required) delete configuration[name];
    else configuration[name] = value;
    onChange(
      selections.map((selection, i) =>
        i === index ? { ...selection, configuration } : selection,
      ),
    );
  };
  const toggle = (action: SemanticAction, checked: boolean) => {
    if (!checked) {
      onChange(
        selections.filter((selection) => selection.actionId !== action.id),
      );
      return;
    }
    const configuration = defaultConfiguration(action);
    onChange([
      ...selections,
      {
        actionId: action.id,
        sha256: action.sha256,
        label: (action.id ?? "").replace(/-/g, "_").slice(0, 48),
        criteria: action.criteria,
        configuration,
      },
    ]);
  };

  const upgrade = (index: number, current: SemanticAction) => {
    onChange(
      selections.map((selection, i) =>
        i === index
          ? {
              ...selection,
              sha256: current.sha256,
              configuration: defaultConfiguration(current),
            }
          : selection,
      ),
    );
  };
  const identifiers = [...new Set(available.map((action) => action.id))];

  return (
    <div className="semantic-router-fields">
      <p>
        Select two to sixteen compatible actions or sink destinations.
        Configuration applies to the deployment, not each request.
      </p>
      {available.length === 0 && (
        <p>
          No eligible actions or sinks are available. Upload a compatible
          Kamelet to the catalog.
        </p>
      )}
      {identifiers.map((id) => {
        const current = available.find(
          (action) => action.id === id && action.current !== false,
        );
        const index = selections.findIndex(
          (selection) => selection.actionId === id,
        );
        const selection = selections[index];
        const action = selection?.sha256
          ? available.find(
              (item) => item.id === id && item.sha256 === selection.sha256,
            )
          : current;
        if (!action) return null;
        const earlier = action.current === false;
        const incompatible = action.profile !== profile;
        return (
          <Tile key={action.id} className="semantic-router-action">
            <Checkbox
              id={`action-${action.id}`}
              labelText={action.name ?? action.id ?? "Unnamed action"}
              checked={index >= 0}
              disabled={incompatible}
              onChange={(_event, { checked }) => toggle(action, checked)}
            />
            {action.type === "sink" && <Tag type="blue">Sink destination</Tag>}
            {earlier && (
              <div className="semantic-router-fields">
                <Tag type="gray">Earlier revision</Tag>
                <p>
                  {current
                    ? "This router keeps its selected revision. Use the current revision to replace its deployment configuration with the new defaults."
                    : "This action was removed from the current catalog. This router keeps its selected revision."}
                </p>
                {current && (
                  <Button
                    kind="tertiary"
                    size="sm"
                    disabled={current.profile !== profile}
                    aria-label={`Use current revision of ${action.name ?? action.id}`}
                    onClick={() => upgrade(index, current)}
                  >
                    Use current revision
                  </Button>
                )}
              </div>
            )}
            <p>{action.description}</p>
            <p>
              {incompatible
                ? "This action has an incompatible input/output profile."
                : `Profile: ${action.profile}`}
            </p>
            {selection && (
              <ActionConfigurationFields
                action={action}
                selection={selection}
                index={index}
                errors={errors}
                onChange={(name, value, required) =>
                  update(index, name, value, required)
                }
              />
            )}
          </Tile>
        );
      })}
    </div>
  );
}
