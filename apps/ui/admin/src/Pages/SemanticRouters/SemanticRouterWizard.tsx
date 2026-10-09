import { useEffect, useRef } from "react";
import {
  Button,
  ComposedModal,
  InlineNotification,
  ModalBody,
  ModalFooter,
  ModalHeader,
  ProgressIndicator,
  ProgressStep,
} from "@carbon/react";
import type {
  SemanticAction,
  SemanticExpert,
  SemanticRouterDefinition,
} from "../../models";
import { ActionStep } from "./ActionStep";
import { IdentityStep } from "./IdentityStep";
import { DecisionStep } from "./DecisionStep";
import { ExamplesStep } from "./ExamplesStep";
import { ReviewStep } from "./ReviewStep";
import {
  belongsToStep,
  useSemanticRouterDraft,
} from "./useSemanticRouterDraft";

interface SemanticRouterWizardProps {
  initial: SemanticRouterDefinition | null;
  actions: SemanticAction[];
  experts: SemanticExpert[];
  onClose: () => void;
  onSaved: () => void;
}

const steps = [
  "Identity",
  "Actions",
  "Semantic decision",
  "Examples",
  "Review and publication",
];
export function SemanticRouterWizard({
  initial,
  actions,
  experts,
  onClose,
  onSaved,
}: SemanticRouterWizardProps) {
  const {
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
  } = useSemanticRouterDraft(initial, onSaved);
  const heading = useRef<HTMLHeadingElement>(null);
  const visibleErrors = errors.filter((item) =>
    belongsToStep(item.field ?? "", step),
  );
  useEffect(() => {
    heading.current?.focus();
  }, [step]);
  return (
    <ComposedModal
      open
      size="lg"
      preventCloseOnClickOutside
      onClose={() => {
        if (!busy) onClose();
      }}
    >
      <ModalHeader
        title={initial ? "Edit semantic router" : "Create semantic router"}
        closeModal={() => {
          if (!busy) onClose();
        }}
      />
      <ModalBody
        hasScrollingContent
        onBlur={(event: React.FocusEvent<HTMLDivElement>) => {
          // Carbon centers the next focused control on blur. Moving a mouse target
          // between pointer down and up loses its click. Keep internal focus still;
          // the modal's focus trap continues to handle focus leaving this region.
          if (
            event.relatedTarget instanceof Node &&
            event.currentTarget.contains(event.relatedTarget)
          ) {
            event.stopPropagation();
          }
        }}
      >
        <ProgressIndicator
          currentIndex={step}
          className="semantic-router-progress"
        >
          {steps.map((label, index) => (
            <ProgressStep
              key={label}
              label={label}
              complete={index < step}
              disabled={busy || index > step}
              onClick={() => {
                if (index < step) setStep(index);
              }}
            />
          ))}
        </ProgressIndicator>
        <h2 ref={heading} tabIndex={-1} className="semantic-router-step-title">
          {steps[step]}
        </h2>
        {error && (
          <InlineNotification
            kind="error"
            title="Request failed"
            subtitle={error}
            hideCloseButton
          />
        )}
        {notice && (
          <InlineNotification kind="success" title={notice} hideCloseButton />
        )}
        {visibleErrors.length > 0 && (
          <div role="alert">
            <p>Correct these fields to continue:</p>
            <ul className="semantic-router-errors">
              {visibleErrors.map((item, index) => (
                <li key={index}>
                  {item.field}: {item.message}
                </li>
              ))}
            </ul>
          </div>
        )}
        {step === 0 && (
          <IdentityStep
            definition={definition}
            errors={errors}
            onChange={change}
          />
        )}
        {step === 1 && (
          <ActionStep
            available={actions}
            selections={definition.actions ?? []}
            profile={definition.profile ?? ""}
            errors={errors}
            onChange={(selections) =>
              change({ ...definition, actions: selections })
            }
          />
        )}
        {step === 2 && (
          <DecisionStep
            definition={definition}
            experts={experts}
            errors={errors}
            onChange={change}
          />
        )}
        {step === 3 && (
          <ExamplesStep
            definition={definition}
            results={previews}
            busy={busy}
            onChange={(examples) => change({ ...definition, examples })}
            onPreview={preview}
          />
        )}
        {step === 4 && (
          <ReviewStep
            definition={definition}
            publication={publication}
            savedName={savedName}
            publicationNotice={publicationNotice}
          />
        )}
      </ModalBody>
      <ModalFooter>
        <Button kind="secondary" disabled={busy} onClick={onClose}>
          {publication ? "Close" : "Cancel"}
        </Button>
        <Button kind="ghost" disabled={busy} onClick={saveDraft}>
          Save draft
        </Button>
        {step > 0 && (
          <Button
            kind="tertiary"
            disabled={busy}
            onClick={() => setStep((current) => current - 1)}
          >
            Back
          </Button>
        )}
        {step < steps.length - 1 ? (
          <Button disabled={busy} onClick={next}>
            {busy ? "Checking…" : "Next"}
          </Button>
        ) : (
          <Button disabled={busy} onClick={publish}>
            {busy ? "Publishing…" : "Publish catalog"}
          </Button>
        )}
      </ModalFooter>
    </ComposedModal>
  );
}
