import {
  InlineNotification,
  Select,
  SelectItem,
  TextArea,
  TextInput,
  Tile,
} from "@carbon/react";
import type {
  SemanticExpert,
  SemanticFieldError,
  SemanticRouterDefinition,
} from "../../models";

interface DecisionStepProps {
  definition: SemanticRouterDefinition;
  experts: SemanticExpert[];
  errors: SemanticFieldError[];
  onChange: (definition: SemanticRouterDefinition) => void;
}

const expertHelp =
  "Expert credentials, model, limits, and connection settings belong to deployment configuration.";
const labelHelp =
  "Use lowercase letters, digits, and underscores. Start with a letter. The no_match label is reserved.";
const criteriaHelp =
  "Describe the requests this action handles. Give the expert clear conditions that distinguish it from the other actions.";
const noMatchHelp =
  "Describe when the expert should return no_match. WSR returns that result without running an action; assignment and logging must be handled by the caller.";
const messageHelp =
  'The expert reads the MCP request message field. For example: "I need a refund for my invoice." This profile always uses that field as the classification input.';
const instructionsHelp =
  "Ask the business question that decides which action label to return. Explain how to use any context. The expert must choose a configured action label or no_match. Use each action's criteria for its specific selection rules.";

export function DecisionStep({
  definition,
  experts,
  errors,
  onChange,
}: DecisionStepProps) {
  const error = (field: string) =>
    errors.find((item) => item.field === field)?.message;
  const selected = experts.find((expert) => expert.id === definition.expertId);
  return (
    <div className="semantic-router-fields">
      <Select
        id="semantic-expert"
        labelText="Configured expert"
        value={definition.expertId ?? ""}
        invalid={Boolean(error("expertId"))}
        invalidText={error("expertId")}
        helperText={expertHelp}
        aria-description={expertHelp}
        onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
          onChange({ ...definition, expertId: event.target.value })
        }
      >
        <SelectItem value="" text="Select an expert" />
        {experts.map((expert) => (
          <SelectItem
            key={expert.id}
            value={expert.id}
            text={expert.name ?? expert.id ?? ""}
          />
        ))}
      </Select>
      {selected && (
        <p>
          Configured instance: {selected.bean}. Dependency:{" "}
          {selected.dependency}.
        </p>
      )}
      <div>
        <Select
          id="semantic-input"
          labelText="Message to classify"
          value={definition.semanticInput ?? "message"}
          aria-description={messageHelp}
          invalid={Boolean(error("semanticInput"))}
          invalidText={
            <>
              {error("semanticInput")}
              <span className="cds--visually-hidden"> {messageHelp}</span>
            </>
          }
          onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
            onChange({ ...definition, semanticInput: event.target.value })
          }
        >
          <SelectItem value="message" text="Request message (message field)" />
        </Select>
        <p className="cds--form__helper-text">{messageHelp}</p>
      </div>
      <div>
        <TextArea
          id="semantic-instructions"
          labelText="Classification instructions"
          aria-description={instructionsHelp}
          placeholder="Which team should handle this support request? If the state is an envelope, classify `message` and use `serviceScope` only as background context."
          value={definition.instructions ?? ""}
          invalid={Boolean(error("instructions"))}
          invalidText={error("instructions")}
          onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
            onChange({ ...definition, instructions: event.target.value })
          }
        />
        <p className="cds--form__helper-text">{instructionsHelp}</p>
      </div>
      {(definition.actions ?? []).map((action, index) => (
        <Tile key={action.actionId} className="semantic-router-fields">
          <h3>{action.actionId}</h3>
          <TextInput
            id={`label-${index}`}
            labelText="Action label"
            value={action.label ?? ""}
            placeholder="billing"
            helperText={labelHelp}
            aria-description={labelHelp}
            invalid={Boolean(error(`actions[${index}].label`))}
            invalidText={error(`actions[${index}].label`)}
            onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
              onChange({
                ...definition,
                actions: definition.actions?.map((item, i) =>
                  i === index ? { ...item, label: event.target.value } : item,
                ),
              })
            }
          />
          <TextArea
            id={`criteria-${index}`}
            labelText={`When to select ${action.label || action.actionId}`}
            value={action.criteria ?? ""}
            placeholder="Select this action when the request falls within this team's area of responsibility."
            helperText={criteriaHelp}
            aria-description={criteriaHelp}
            invalid={Boolean(error(`actions[${index}].criteria`))}
            invalidText={error(`actions[${index}].criteria`)}
            onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
              onChange({
                ...definition,
                actions: definition.actions?.map((item, i) =>
                  i === index
                    ? { ...item, criteria: event.target.value }
                    : item,
                ),
              })
            }
          />
        </Tile>
      ))}
      <TextArea
        id="no-match-criteria"
        labelText="When no action matches"
        value={definition.noMatchCriteria ?? ""}
        placeholder="Assign to other and log the request"
        helperText={noMatchHelp}
        aria-description={noMatchHelp}
        invalid={Boolean(error("noMatchCriteria"))}
        invalidText={error("noMatchCriteria")}
        onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
          onChange({ ...definition, noMatchCriteria: event.target.value })
        }
      />
      <InlineNotification
        kind="info"
        title="No match and failures"
        hideCloseButton
        subtitle="No match returns no_match and executes no action. Provider failures and malformed decisions return evaluation errors. Action failures return execution errors."
      />
    </div>
  );
}
