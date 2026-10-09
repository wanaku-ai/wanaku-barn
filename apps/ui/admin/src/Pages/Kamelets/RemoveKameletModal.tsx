import { useState } from "react";
import { InlineNotification, Modal } from "@carbon/react";
import type { KameletSummary } from "../../models";
import { getErrorMessage } from "../../utils/error";
import { kameletApi } from "./api";

interface RemoveKameletModalProps {
  kamelet: KameletSummary;
  onClose: () => void;
  onRemoved: () => void;
}

export function RemoveKameletModal({
  kamelet,
  onClose,
  onRemoved,
}: RemoveKameletModalProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const remove = async () => {
    if (!kamelet.name || busy) return;
    setBusy(true);
    setError(null);
    try {
      await kameletApi.remove(kamelet.name);
      onRemoved();
    } catch (cause) {
      setError(getErrorMessage(cause));
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      danger
      modalHeading="Remove Kamelet?"
      primaryButtonText={busy ? "Removing…" : "Remove"}
      secondaryButtonText="Cancel"
      primaryButtonDisabled={busy}
      preventCloseOnClickOutside
      onRequestSubmit={() => void remove()}
      onRequestClose={() => {
        if (!busy) onClose();
      }}
    >
      <p>
        Remove {kamelet.name} from the current catalog? Existing routers and
        published catalogs keep their selected revision.
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
  );
}
