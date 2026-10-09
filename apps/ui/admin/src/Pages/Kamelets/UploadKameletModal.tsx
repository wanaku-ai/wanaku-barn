import { useState } from "react";
import {
  FileUploaderDropContainer,
  FileUploaderItem,
  InlineLoading,
  InlineNotification,
  Modal,
} from "@carbon/react";
import { getErrorMessage } from "../../utils/error";
import { kameletApi } from "./api";

interface UploadKameletModalProps {
  onClose: () => void;
  onUploaded: () => void;
}

type UploadState =
  | { status: "idle" }
  | { status: "uploading" }
  | { status: "error"; error: string };
const maximumBytes = 1024 * 1024;
// Native file pickers filter by the final extension; validate the Kamelet suffix below.
const acceptedTypes = [".yaml"];
const fileHint =
  "Select one UTF-8 .kamelet.yaml file of 1 MiB or less. Barn reads the Kamelet name from its metadata.";

function fileError(file: File): string | null {
  if (!/\.kamelet\.yaml$/i.test(file.name))
    return "Select a .kamelet.yaml file.";
  if (file.size > maximumBytes) return "The file must be 1 MiB or less.";
  return null;
}

export function UploadKameletModal({
  onClose,
  onUploaded,
}: UploadKameletModalProps) {
  const [file, setFile] = useState<File | null>(null);
  const [state, setState] = useState<UploadState>({ status: "idle" });
  const busy = state.status === "uploading";
  const invalid = file ? fileError(file) : null;

  const upload = async () => {
    if (!file || busy || invalid) return;
    setState({ status: "uploading" });
    try {
      const yaml = new TextDecoder("utf-8", {
        fatal: true,
        ignoreBOM: true,
      }).decode(await file.arrayBuffer());
      await kameletApi.upload(yaml);
      onUploaded();
    } catch (cause) {
      setState({ status: "error", error: getErrorMessage(cause) });
    }
  };

  return (
    <Modal
      open
      modalHeading="Upload Kamelet"
      primaryButtonText={busy ? "Uploading…" : "Upload"}
      secondaryButtonText="Cancel"
      primaryButtonDisabled={!file || busy || Boolean(invalid)}
      preventCloseOnClickOutside
      onRequestSubmit={() => void upload()}
      onRequestClose={() => {
        if (!busy) onClose();
      }}
    >
      <div className="kamelet-upload-fields">
        <p id="kamelet-file-hint">{fileHint}</p>
        <p>
          Uploading a changed Kamelet creates a new current revision. Existing
          routers keep their selected revision.
        </p>
        <FileUploaderDropContainer
          id="kamelet-file"
          labelText="Select or drop a Kamelet file"
          aria-describedby="kamelet-file-hint"
          accept={acceptedTypes}
          multiple={false}
          maxFileSize={maximumBytes}
          disabled={busy}
          onAddFiles={(_event, { addedFiles }) => {
            setFile(addedFiles.length === 1 ? addedFiles[0] : null);
            setState(
              addedFiles.length === 1
                ? { status: "idle" }
                : { status: "error", error: "Select one Kamelet file." },
            );
          }}
        />
        {file && (
          <FileUploaderItem
            name={file.name}
            uuid="kamelet-upload"
            status={busy ? "uploading" : "edit"}
            disabled={busy}
            invalid={Boolean(invalid)}
            errorSubject="Invalid file"
            errorBody={invalid ?? undefined}
            iconDescription="Remove selected file"
            onDelete={() => {
              setFile(null);
              setState({ status: "idle" });
            }}
          />
        )}
        <div aria-live="polite">
          {busy && <InlineLoading description="Uploading Kamelet" />}
          {state.status === "error" && (
            <InlineNotification
              kind="error"
              title="Upload failed"
              subtitle={state.error}
              hideCloseButton
            />
          )}
        </div>
      </div>
    </Modal>
  );
}
