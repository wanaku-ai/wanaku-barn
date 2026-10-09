import { useEffect, useRef, useState } from "react";
import type {
  SemanticFieldError,
  SemanticPublication,
  SemanticRouterDefinition,
} from "../../models";
import { getErrorMessage } from "../../utils/error";
import { semanticRouterApi } from "./api";
import type { PreviewState } from "./ExamplesStep";

const blank: SemanticRouterDefinition = {
  name: "",
  description: "",
  toolName: "",
  profile: "message-to-string/v1",
  expertId: "",
  semanticInput: "message",
  instructions: "",
  noMatchCriteria: "",
  actions: [],
  examples: [],
};

export function belongsToStep(field: string, step: number): boolean {
  if (step === 0)
    return ["name", "description", "toolName", "profile"].includes(field);
  if (step === 1)
    return (
      field === "actions" ||
      field.includes(".configuration") ||
      field.includes(".actionId")
    );
  if (step === 2)
    return (
      !field.startsWith("examples") &&
      !belongsToStep(field, 0) &&
      !belongsToStep(field, 1)
    );
  return step === 3 ? field.startsWith("examples") : true;
}

export function useSemanticRouterDraft(
  initial: SemanticRouterDefinition | null,
  onSaved: () => void,
) {
  const [definition, setDefinition] = useState<SemanticRouterDefinition>(
    () => initial ?? blank,
  );
  const [step, setStep] = useState(0);
  const [busy, setBusy] = useState(false);
  const [errors, setErrors] = useState<SemanticFieldError[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [publication, setPublication] = useState<SemanticPublication | null>(
    null,
  );
  const [savedName, setSavedName] = useState(initial?.name ?? "");
  const [publicationNotice, setPublicationNotice] = useState<string | null>(
    null,
  );
  const publicationEpoch = useRef(0);
  const [previews, setPreviews] = useState<Record<number, PreviewState>>({});
  useEffect(() => {
    if (initial?.id) {
      let active = true;
      const epoch = publicationEpoch.current;
      semanticRouterApi
        .revisions(initial.id)
        .then(async (revisions) => {
          if (!active || epoch !== publicationEpoch.current) return;
          if (revisions.length === 0) {
            setPublication(null);
            return;
          }
          const selected = await semanticRouterApi.resolve(initial.name ?? "");
          const current = revisions.find(
            (revision) =>
              revision.revision === selected.revision &&
              revision.catalogName === selected.catalogName &&
              revision.sha256 === selected.sha256 &&
              revision.mainFile === selected.mainFile,
          );
          if (!current)
            throw new Error(
              "The selected publication is absent from this router's history.",
            );
          if (active && epoch === publicationEpoch.current) {
            setPublication({
              ...current,
              toolName: selected.toolName,
              expert: selected.expert,
            });
            setPublicationNotice(null);
          }
        })
        .catch((cause) => {
          if (active && epoch === publicationEpoch.current) {
            setPublication(null);
            setPublicationNotice(
              `The current published revision is not available. ${getErrorMessage(cause)}`,
            );
          }
        });
      return () => {
        active = false;
      };
    }
  }, [initial?.id, initial?.name]);

  const change = (next: SemanticRouterDefinition) => {
    setDefinition(next);
    setErrors([]);
    setPreviews({});
    setNotice(null);
  };
  const save = async () => {
    const saved = await semanticRouterApi.save(definition);
    setDefinition(saved);
    setSavedName(saved.name ?? "");
    onSaved();
    return saved;
  };
  const validate = async (currentStep?: number) => {
    const validation = await semanticRouterApi.validate(definition);
    const nextErrors = validation.errors ?? [];
    setErrors(nextErrors);
    return currentStep === undefined
      ? validation.valid
      : !nextErrors.some((item) =>
          belongsToStep(item.field ?? "", currentStep),
        );
  };
  const perform = async (operation: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await operation();
    } catch (cause) {
      setError(getErrorMessage(cause));
    } finally {
      setBusy(false);
    }
  };
  const next = () =>
    perform(async () => {
      if (await validate(step)) setStep((current) => Math.min(current + 1, 4));
    });
  const preview = (index: number) =>
    perform(async () => {
      if (!(await validate())) {
        setError(
          "Complete the required fields before running a preview. Return to the earlier steps to fix validation errors.",
        );
        return;
      }
      setPreviews((current) => ({
        ...current,
        [index]: { status: "loading" },
      }));
      try {
        const saved = await save();
        if (!saved.id) throw new Error("The saved draft has no identifier.");
        const result = await semanticRouterApi.preview(
          saved.id,
          definition.examples?.[index]?.message ?? "",
        );
        setPreviews((current) => ({
          ...current,
          [index]: { status: "success", data: result },
        }));
      } catch (cause) {
        setPreviews((current) => ({
          ...current,
          [index]: { status: "error", error: getErrorMessage(cause) },
        }));
      }
    });
  const publish = () =>
    perform(async () => {
      if (!(await validate())) return;
      publicationEpoch.current += 1;
      const saved = await save();
      if (!saved.id) throw new Error("The saved draft has no identifier.");
      setPublication(await semanticRouterApi.publish(saved.id));
      setPublicationNotice(null);
      onSaved();
    });
  const saveDraft = () =>
    perform(async () => {
      await save();
      setNotice("Draft saved");
    });
  return {
    definition,
    step,
    setStep,
    busy,
    errors,
    error,
    notice,
    publication,
    publicationNotice,
    savedName,
    previews,
    change,
    saveDraft,
    next,
    preview,
    publish,
  };
}
