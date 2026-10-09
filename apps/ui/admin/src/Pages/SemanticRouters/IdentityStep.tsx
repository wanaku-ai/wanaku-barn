import { TextArea, TextInput } from "@carbon/react";
import type {
  SemanticFieldError,
  SemanticRouterDefinition,
} from "../../models";

interface IdentityStepProps {
  definition: SemanticRouterDefinition;
  errors: SemanticFieldError[];
  onChange: (definition: SemanticRouterDefinition) => void;
}

const purposeHelp =
  "Describe what this router does and when an agent should use it.";

const nameHint =
  "Start with a letter. Use only letters, digits, hyphens, and underscores; do not use spaces.";

function nameError(
  value: string | undefined,
  limit: number,
): string | undefined {
  if (value && !/^[A-Za-z][A-Za-z0-9_-]*$/.test(value)) return nameHint;
  if (value && value.length > limit) return `Use at most ${limit} characters.`;
}

export function IdentityStep({
  definition,
  errors,
  onChange,
}: IdentityStepProps) {
  const error = (field: string) =>
    errors.find((item) => item.field === field)?.message;
  const routerError = nameError(definition.name, 120) || error("name");
  const toolError = nameError(definition.toolName, 64) || error("toolName");
  return (
    <div className="semantic-router-fields">
      <TextInput
        id="router-name"
        labelText="Router name"
        value={definition.name ?? ""}
        placeholder="support-route"
        helperText={`${nameHint} Maximum 120 characters. Example: support-route.`}
        aria-description={`${nameHint} Maximum 120 characters. Example: support-route.`}
        pattern={"[A-Za-z][A-Za-z0-9_\\-]*"}
        maxLength={120}
        invalid={Boolean(routerError)}
        invalidText={routerError}
        onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
          onChange({ ...definition, name: event.target.value })
        }
      />
      <TextArea
        id="router-description"
        labelText="Business purpose"
        value={definition.description ?? ""}
        placeholder="Route support requests to the team that can resolve them."
        helperText={purposeHelp}
        aria-description={purposeHelp}
        invalid={Boolean(error("description"))}
        invalidText={error("description")}
        onChange={(event: React.ChangeEvent<HTMLTextAreaElement>) =>
          onChange({ ...definition, description: event.target.value })
        }
      />
      <TextInput
        id="router-tool"
        labelText="MCP tool name"
        value={definition.toolName ?? ""}
        placeholder="support-request"
        pattern={"[A-Za-z][A-Za-z0-9_\\-]*"}
        maxLength={64}
        invalid={Boolean(toolError)}
        invalidText={toolError}
        helperText={`Agents call this tool with a request message. ${nameHint} Maximum 64 characters. Example: support-request.`}
        aria-description={`${nameHint} Maximum 64 characters. Example: support-request.`}
        onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
          onChange({ ...definition, toolName: event.target.value })
        }
      />
      <p>
        Input/output profile: {definition.profile}. One selected action runs for
        each request.
      </p>
    </div>
  );
}
