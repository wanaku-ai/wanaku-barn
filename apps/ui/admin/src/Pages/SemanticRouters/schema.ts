import type { SemanticAction } from "../../models";

interface ConfigurationField {
  name: string;
  title: string;
  description: string;
  type: string;
  required: boolean;
  secretReference: boolean;
  options: string[];
  defaultValue: string;
}

function record(value: unknown): Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : {};
}

/** Read the curated Kamelet's native schema. Credentials remain environment references. */
export function configurationFields(
  action: SemanticAction,
): ConfigurationField[] {
  const schema = record(action.configurationSchema);
  const required = Array.isArray(schema.required) ? schema.required : [];
  return Object.entries(record(schema.properties)).map(([name, value]) => {
    const property = record(value);
    return {
      name,
      title: typeof property.title === "string" ? property.title : name,
      description:
        typeof property.description === "string" ? property.description : "",
      type: typeof property.type === "string" ? property.type : "string",
      required: required.includes(name),
      secretReference: property["x-secret-reference"] === true,
      options: Array.isArray(property.enum) ? property.enum.map(String) : [],
      defaultValue:
        property.default === undefined ? "" : String(property.default),
    };
  });
}
