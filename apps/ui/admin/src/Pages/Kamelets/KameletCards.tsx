import { Button, Column, Grid, Tag, Tile } from "@carbon/react";
import { Download, TrashCan } from "@carbon/react/icons";
import type { KameletSummary } from "../../models";

interface KameletCardsProps {
  kamelets: KameletSummary[];
  downloading: string | null;
  filtered: boolean;
  onDownload: (kamelet: KameletSummary) => void;
  onRemove: (kamelet: KameletSummary) => void;
}

export function KameletCards({
  kamelets,
  downloading,
  filtered,
  onDownload,
  onRemove,
}: KameletCardsProps) {
  return (
    <Grid fullWidth className="kamelets-grid">
      {kamelets.length === 0 && (
        <Column sm={4} md={8} lg={16}>
          <Tile>
            <p>
              {filtered
                ? "No Kamelets match your search."
                : "No Kamelets are available. Upload a Kamelet to add it to the catalog."}
            </p>
          </Tile>
        </Column>
      )}
      {kamelets.map((kamelet) => (
        <Column
          sm={4}
          md={4}
          lg={4}
          key={kamelet.name}
          className="kamelet-column"
        >
          <Tile className="kamelet-card">
            <h2>{kamelet.title || kamelet.name}</h2>
            <p>{kamelet.name}</p>
            <p>{kamelet.description || "No description supplied."}</p>
            <div className="kamelet-tags">
              <Tag type="blue">{kamelet.type || "Kamelet"}</Tag>
              <Tag type="gray">{kamelet.source || "Catalog"}</Tag>
              <Tag type={kamelet.semanticEligible ? "green" : "gray"}>
                {kamelet.semanticEligible
                  ? "Semantic action"
                  : "Not eligible for semantic routing"}
              </Tag>
            </div>
            {kamelet.semanticEligibilityReason && (
              <p>{kamelet.semanticEligibilityReason}</p>
            )}
            <div className="kamelet-actions">
              <Button
                kind="tertiary"
                size="sm"
                renderIcon={Download}
                aria-label={`Download ${kamelet.name}`}
                disabled={downloading !== null || !kamelet.name}
                onClick={() => onDownload(kamelet)}
              >
                {downloading === kamelet.name ? "Preparing…" : "Download YAML"}
              </Button>
              <Button
                kind="danger--ghost"
                size="sm"
                renderIcon={TrashCan}
                aria-label={`Remove ${kamelet.name}`}
                disabled={!kamelet.removable}
                onClick={() => onRemove(kamelet)}
              >
                Remove
              </Button>
            </div>
            {!kamelet.removable && (
              <p>This Kamelet is supplied by the server configuration.</p>
            )}
          </Tile>
        </Column>
      ))}
    </Grid>
  );
}
