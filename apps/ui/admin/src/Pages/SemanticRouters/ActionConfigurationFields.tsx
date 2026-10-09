import { Select, SelectItem, TextInput } from "@carbon/react";
import type {
  SemanticAction,
  SemanticActionSelection,
  SemanticFieldError,
} from "../../models";
import { configurationFields } from "./schema";

interface ActionConfigurationFieldsProps {
  action: SemanticAction;
  selection: SemanticActionSelection;
  index: number;
  errors: SemanticFieldError[];
  onChange: (name: string, value: string, required: boolean) => void;
}

function configurationHints(
  field: ReturnType<typeof configurationFields>[number],
  title: string,
  numeric: boolean,
): { helper: string; placeholder: string } {
  if (field.secretReference)
    return {
      helper: "Use env:VARIABLE. Do not enter a credential value.",
      placeholder: "env:VARIABLE",
    };
  return {
    helper:
      field.description.trim() ||
      `Set ${title} for this action. This value applies to every request.`,
    placeholder:
      field.defaultValue || (numeric ? "Enter a number" : `Enter ${title}`),
  };
}

export function ActionConfigurationFields({
  action,
  selection,
  index,
  errors,
  onChange,
}: ActionConfigurationFieldsProps) {
  return configurationFields(action).map((field) => {
    const error = errors.find(
      (item) => item.field === `actions[${index}].configuration.${field.name}`,
    )?.message;
    const value = selection.configuration?.[field.name] ?? field.defaultValue;
    const id = `config-${action.id}-${field.name}`;
    const title = field.title.trim() || field.name;
    const numeric = field.type === "integer" || field.type === "number";
    const label = `${title}${field.required ? " (required)" : ""}`;
    const { helper, placeholder } = configurationHints(field, title, numeric);
    if (field.options.length || field.type === "boolean") {
      const options = field.options.length ? field.options : ["true", "false"];
      return (
        <Select
          key={id}
          id={id}
          labelText={label}
          helperText={helper}
          aria-description={helper}
          value={value}
          invalid={Boolean(error)}
          invalidText={error}
          onChange={(event: React.ChangeEvent<HTMLSelectElement>) =>
            onChange(field.name, event.target.value, field.required)
          }
        >
          <SelectItem value="" text="Select a value" />
          {options.map((option) => (
            <SelectItem key={option} value={option} text={option} />
          ))}
        </Select>
      );
    }
    return (
      <TextInput
        key={id}
        id={id}
        labelText={label}
        helperText={helper}
        aria-description={helper}
        placeholder={placeholder}
        value={value}
        type={numeric ? "number" : "text"}
        invalid={Boolean(error)}
        invalidText={error}
        onChange={(event: React.ChangeEvent<HTMLInputElement>) =>
          onChange(field.name, event.target.value, field.required)
        }
      />
    );
  });
}
