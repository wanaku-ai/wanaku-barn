import {
  Accordion,
  AccordionItem,
  CodeSnippet,
  InlineNotification,
  StructuredListBody,
  StructuredListCell,
  StructuredListRow,
  StructuredListWrapper,
} from "@carbon/react";
import type {
  SemanticPublication,
  SemanticRouterDefinition,
} from "../../models";

import { GeneratedFiles } from "./GeneratedFiles";

interface ReviewStepProps {
  definition: SemanticRouterDefinition;
  publication: SemanticPublication | null;
  savedName: string;
  publicationNotice: string | null;
}

function shellQuote(value: string): string {
  return "'" + value.replace(/'/g, "'\"'\"'") + "'";
}

export function ReviewStep({
  definition,
  publication,
  savedName,
  publicationNotice,
}: ReviewStepProps) {
  const rows = [
    ["Name", definition.name],
    ["MCP tool", definition.toolName],
    ["Compatibility profile", definition.profile],
    ["Expert", definition.expertId],
    ["Message to classify", "Request message (message field)"],
    [
      "Action labels",
      definition.actions?.map((action) => action.label).join(", "),
    ],
    ["Saved examples", String(definition.examples?.length ?? 0)],
  ];
  return (
    <div className="semantic-router-fields">
      <StructuredListWrapper aria-label="Router review">
        <StructuredListBody>
          {rows.map(([label, value]) => (
            <StructuredListRow key={label}>
              <StructuredListCell>{label}</StructuredListCell>
              <StructuredListCell>{value}</StructuredListCell>
            </StructuredListRow>
          ))}
        </StructuredListBody>
      </StructuredListWrapper>
      <InlineNotification
        kind="info"
        title="Publication creates a catalog"
        hideCloseButton
        subtitle="Deploy the selected revision to WSR. WSR must start successfully before it registers with Wanaku. Publishing does not activate a runtime."
      />
      <GeneratedFiles definition={definition} />
      {publicationNotice && (
        <InlineNotification
          kind="warning"
          title="Current publication not observed"
          subtitle={`${publicationNotice} Publish this draft or select an explicit revision in WSR.`}
          hideCloseButton
        />
      )}
      {publication && (
        <section
          aria-label="Published catalog"
          className="semantic-router-fields"
        >
          <InlineNotification
            kind="success"
            title="Catalog published"
            subtitle={`Revision: ${publication.revision}`}
            hideCloseButton
          />
          <p>Catalog: {publication.catalogName}</p>
          <p>
            SHA-256:{" "}
            <code className="semantic-router-digest">{publication.sha256}</code>
          </p>
          <p>
            Runtime status: not observed. This is the current published
            revision; further draft edits require publication.
          </p>
          <h3>Deployment instructions</h3>
          <p>Start the current published route:</p>
          <CodeSnippet type="multi" feedback="Copied">
            {`java -jar wanaku-semantic-router-0.1.0-SNAPSHOT.jar runtime --semantic-route=${shellQuote(savedName)}`}
          </CodeSnippet>
          <p>
            To select the displayed revision, add{" "}
            <code>{`--catalog-revision=${shellQuote(publication.revision ?? "")}`}</code>
            .
          </p>
          {publication.expert?.dependency ===
          `org.apache.camel:camel-typesafe-ai:${publication.camelVersion}` ? (
            <p>
              Set TYPESAFE_API_KEY in the runtime environment. Set
              TYPESAFE_MODEL to override the default model.
            </p>
          ) : (
            <p>
              Configure the --expert option for this publication. Set provider
              credentials in the runtime environment.
            </p>
          )}
          {(publication.deploymentInstructions?.length ?? 0) > 0 && (
            <Accordion>
              <AccordionItem title="Recorded deployment settings">
                {(publication.deploymentInstructions ?? []).map(
                  (instruction, index) => (
                    <CodeSnippet key={index} type="multi" feedback="Copied">
                      {instruction}
                    </CodeSnippet>
                  ),
                )}
              </AccordionItem>
            </Accordion>
          )}
        </section>
      )}
    </div>
  );
}
