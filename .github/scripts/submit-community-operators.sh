#!/usr/bin/env bash
# Opens a pull request that adds the Wanaku Operator bundle to a community-operators repository:
#   - k8s-operatorhub/community-operators                       (OperatorHub.io, upstream Kubernetes)
#   - redhat-openshift-ecosystem/community-operators-prod       (embedded OperatorHub in OpenShift/OKD)
#
# Required environment:
#   TARGET_REPO  upstream repository, for example k8s-operatorhub/community-operators
#   FORK_OWNER   GitHub user or organization that owns a fork of TARGET_REPO (same repository name)
#   VERSION      bundle version, for example 0.3.0
#   BUNDLE_DIR   directory with manifests/, metadata/ and tests/ (apps/wanaku-operator/target/bundle/wanaku-operator)
#   GH_TOKEN     token that can push to the fork and open pull requests on TARGET_REPO
#   GIT_NAME, GIT_EMAIL   identity used for the DCO sign-off of the commit
# Optional:
#   REVIEWERS    comma-separated GitHub users written to ci.yaml when the package is submitted for the first time
#   DRY_RUN      when "true", prepares the commit locally but neither pushes nor opens the pull request

set -euo pipefail

: "${TARGET_REPO:?}" "${FORK_OWNER:?}" "${VERSION:?}" "${BUNDLE_DIR:?}" "${GH_TOKEN:?}" "${GIT_NAME:?}" "${GIT_EMAIL:?}"
REVIEWERS="${REVIEWERS:-orpiske}"
DRY_RUN="${DRY_RUN:-false}"
PACKAGE=wanaku-operator

[[ "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] || { echo "VERSION '${VERSION}' is not semver" >&2; exit 1; }
[[ -d "${BUNDLE_DIR}/manifests" && -d "${BUNDLE_DIR}/metadata" ]] || { echo "${BUNDLE_DIR} is not a bundle" >&2; exit 1; }

REPO_NAME="${TARGET_REPO#*/}"
FORK="${FORK_OWNER}/${REPO_NAME}"
BRANCH="${PACKAGE}-${VERSION}"
BUNDLE_DIR="$(cd "${BUNDLE_DIR}" && pwd)"

gh repo view "${FORK}" >/dev/null 2>&1 || {
  echo "The fork ${FORK} does not exist. Fork ${TARGET_REPO} to ${FORK_OWNER} first." >&2
  exit 1
}

echo "==> Syncing ${FORK} with ${TARGET_REPO}"
gh repo sync "${FORK}" --branch main

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
gh auth setup-git
# The repositories are large; fetch only the package directory
git clone --depth 1 --filter=blob:none --sparse "https://github.com/${FORK}.git" "${WORK}/repo"
cd "${WORK}/repo"
git sparse-checkout set "operators/${PACKAGE}"
git config user.name "${GIT_NAME}"
git config user.email "${GIT_EMAIL}"
git checkout -b "${BRANCH}"

DEST="operators/${PACKAGE}/${VERSION}"
if [[ -e "${DEST}" ]]; then
  echo "${DEST} already exists in ${TARGET_REPO}" >&2
  exit 1
fi
mkdir -p "${DEST}"
cp -r "${BUNDLE_DIR}/manifests" "${BUNDLE_DIR}/metadata" "${DEST}/"
[[ -d "${BUNDLE_DIR}/tests" ]] && cp -r "${BUNDLE_DIR}/tests" "${DEST}/"

CI_FILE="operators/${PACKAGE}/ci.yaml"
if [[ ! -f "${CI_FILE}" ]]; then
  {
    echo "---"
    echo "# Use \`replaces-mode\` or \`semver-mode\`. Once you switch to \`semver-mode\`, there is no easy way back."
    echo "updateGraph: replaces-mode"
    echo "addReviewers: true"
    echo "reviewers:"
    IFS=',' read -ra users <<< "${REVIEWERS}"
    for user in "${users[@]}"; do echo "  - ${user}"; done
  } > "${CI_FILE}"
fi

git add "operators/${PACKAGE}"
git commit --signoff -m "operator ${PACKAGE} (${VERSION})"

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "==> Dry run: prepared the commit but did not push"
  git show --stat HEAD
  exit 0
fi

git push --force-with-lease origin "${BRANCH}"

BODY="$(mktemp)"
cat > "${BODY}" <<EOF
## Wanaku Operator ${VERSION}

Adds the \`${PACKAGE}\` ${VERSION} bundle, generated from https://github.com/wanaku-ai/wanaku-barn
(\`apps/wanaku-operator\`, see \`docs/operate/operator-olm.md\`).

### Checklist

- [x] The bundle passes \`operator-sdk bundle validate --select-optional suite=operatorframework\`
- [x] The bundle follows the [bundle format](https://k8s-operatorhub.github.io/community-operators/packaging-operator/)
- [x] The CSV has the [fields required by OperatorHub](https://k8s-operatorhub.github.io/community-operators/packaging-required-fields/)
- [x] The commit is signed off (DCO)
- [x] The operator image \`quay.io/wanaku/wanaku-operator:${VERSION}\` is published
EOF

gh pr create --repo "${TARGET_REPO}" --base main --head "${FORK_OWNER}:${BRANCH}" \
  --title "operator ${PACKAGE} (${VERSION})" --body-file "${BODY}"
