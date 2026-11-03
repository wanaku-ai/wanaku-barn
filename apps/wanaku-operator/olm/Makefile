# OLM bundle automation for the Wanaku Operator.
#
# Run from the repository root through the delegating targets (make olm-bundle, make olm-validate, ...)
# or directly: make -C apps/wanaku-operator/olm <target>
#
# Requirements: mvn, operator-sdk, podman (or docker, set CONTAINER_TOOL=docker). Local testing also needs
# minikube (docker) or crc (+ oc, podman).

REPO_ROOT    := $(abspath $(dir $(lastword $(MAKEFILE_LIST)))/../../..)
OPERATOR_DIR := $(REPO_ROOT)/apps/wanaku-operator
OLM_DIR      := $(OPERATOR_DIR)/olm
BUNDLE_DIR   := $(OPERATOR_DIR)/target/bundle/wanaku-operator

# Release version of the bundle: the project version without -SNAPSHOT unless overridden.
PROJECT_VERSION := $(shell cd $(REPO_ROOT) && mvn -q -o help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)
VERSION         ?= $(patsubst %-SNAPSHOT,%,$(PROJECT_VERSION))

REGISTRY        ?= quay.io
GROUP           ?= wanaku
OPERATOR_IMAGE  ?= $(REGISTRY)/$(GROUP)/wanaku-operator:$(VERSION)
BUNDLE_IMAGE    ?= $(REGISTRY)/$(GROUP)/wanaku-operator-bundle:$(VERSION)

CHANNEL           ?= alpha
REPLACES          ?=
OPERAND_TAG       ?=
CREATED_AT        ?=
SKIP_RANGE        ?=
OPENSHIFT_VERSIONS ?= v4.12
CONTAINER_TOOL    ?= podman
MVN_EXTRA         ?=

# Local clusters (see scripts/local-minikube.sh and scripts/local-crc.sh)
NAMESPACE ?= wanaku-olm-test

.PHONY: help bundle validate verify-bundle bundle-image push-bundle-image scorecard \
        minikube-up minikube-smoke minikube-down crc-up crc-smoke crc-down clean

help:
	@echo "Targets: bundle validate bundle-image push-bundle-image scorecard"
	@echo "         minikube-up minikube-smoke minikube-down crc-up crc-smoke crc-down clean"
	@echo "Variables: VERSION=$(VERSION) OPERATOR_IMAGE=$(OPERATOR_IMAGE) BUNDLE_IMAGE=$(BUNDLE_IMAGE) CHANNEL=$(CHANNEL)"

# Generates and completes the bundle in apps/wanaku-operator/target/bundle/wanaku-operator
# createdAt comes from application.properties unless CREATED_AT is given. replaces and olm.skipRange are optional.
bundle:
	rm -rf $(OPERATOR_DIR)/target/bundle
	cd $(REPO_ROOT) && mvn --no-transfer-progress -pl apps/wanaku-operator -am -DskipTests -Dspotless.check.skip=true \
		-Dquarkus.container-image.registry=$(REGISTRY) \
		-Dquarkus.container-image.group=$(GROUP) \
		-Dquarkus.container-image.tag=$(VERSION) \
		-Dquarkus.operator-sdk.bundle.version=$(VERSION) \
		-Dquarkus.operator-sdk.bundle.channels=$(CHANNEL) \
		-Dquarkus.operator-sdk.bundle.default-channel=$(CHANNEL) \
		'-Dquarkus.operator-sdk.bundle.bundles."QOSDK_DEFAULT".annotations."containerImage"=$(OPERATOR_IMAGE)' \
		$(if $(CREATED_AT),-Dolm.createdAt=$(CREATED_AT)) \
		$(if $(REPLACES),-Dquarkus.operator-sdk.bundle.replaces=$(REPLACES)) \
		$(if $(SKIP_RANGE),'-Dquarkus.operator-sdk.bundle.bundles."QOSDK_DEFAULT".annotations."olm.skipRange"=$(SKIP_RANGE)') \
		$(MVN_EXTRA) \
		package
	OPENSHIFT_VERSIONS=$(OPENSHIFT_VERSIONS) OPERAND_TAG=$(OPERAND_TAG) $(OLM_DIR)/scripts/finish-bundle.sh $(BUNDLE_DIR)

# Verifies that finish-bundle.sh produced the expected artifacts in the bundle
verify-bundle:
	@CSV="$$(ls $(BUNDLE_DIR)/manifests/*.clusterserviceversion.yaml 2>/dev/null)" && \
	  [ -n "$${CSV}" ] || { echo "FAIL: no CSV found in $(BUNDLE_DIR)/manifests"; exit 1; } && \
	  echo "Checking $${CSV}" && \
	  grep -q '  relatedImages:' "$${CSV}" || { echo "FAIL: CSV is missing spec.relatedImages"; exit 1; } && \
	  grep -q '  - name: wanaku-operator' "$${CSV}" || { echo "FAIL: relatedImages missing wanaku-operator entry"; exit 1; } && \
	  echo "PASS: CSV has relatedImages"
	@DOCKERFILE="$(BUNDLE_DIR)/bundle.Dockerfile" && \
	  grep -q 'com.redhat.openshift.versions' "$${DOCKERFILE}" || { echo "FAIL: Dockerfile missing OpenShift versions label"; exit 1; } && \
	  grep -q 'operators.operatorframework.io.test.mediatype.v1' "$${DOCKERFILE}" || { echo "FAIL: Dockerfile missing scorecard labels"; exit 1; } && \
	  echo "PASS: Dockerfile has OpenShift and scorecard labels"
	@[ -f "$(BUNDLE_DIR)/tests/scorecard/config.yaml" ] || { echo "FAIL: scorecard config not copied into bundle"; exit 1; } && \
	  echo "PASS: scorecard config present"
	@ANNOTATIONS="$(BUNDLE_DIR)/metadata/annotations.yaml" && \
	  grep -q 'com.redhat.openshift.versions' "$${ANNOTATIONS}" || { echo "FAIL: annotations.yaml missing OpenShift versions"; exit 1; } && \
	  echo "PASS: annotations.yaml has OpenShift versions"
	@echo "verify-bundle: all checks passed"

# Same checks that the community-operators pipeline runs
validate:
	operator-sdk bundle validate $(BUNDLE_DIR) --select-optional suite=operatorframework --select-optional name=operatorhubv2 --select-optional name=capabilities  --select-optional name=categories  --select-optional name=good-practices

bundle-image:
	cd $(BUNDLE_DIR) && $(CONTAINER_TOOL) build -f bundle.Dockerfile -t $(BUNDLE_IMAGE) .

push-bundle-image:
	$(CONTAINER_TOOL) push $(BUNDLE_IMAGE)

# Needs a cluster (use after minikube-up or crc-up)
scorecard:
	operator-sdk scorecard $(BUNDLE_DIR) --namespace $(NAMESPACE) --wait-time 300s

minikube-up:
	NAMESPACE=$(NAMESPACE) CHANNEL=$(CHANNEL) $(OLM_DIR)/scripts/local-minikube.sh up

minikube-smoke:
	NAMESPACE=$(NAMESPACE) $(OLM_DIR)/scripts/smoke-test.sh kubectl

minikube-down:
	NAMESPACE=$(NAMESPACE) $(OLM_DIR)/scripts/local-minikube.sh down

crc-up:
	NAMESPACE=$(NAMESPACE) CHANNEL=$(CHANNEL) $(OLM_DIR)/scripts/local-crc.sh up

crc-smoke:
	NAMESPACE=$(NAMESPACE) $(OLM_DIR)/scripts/smoke-test.sh oc

crc-down:
	NAMESPACE=$(NAMESPACE) $(OLM_DIR)/scripts/local-crc.sh down

clean:
	rm -rf $(OPERATOR_DIR)/target/bundle
