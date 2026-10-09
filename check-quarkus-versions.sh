#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
POM_FILE="${SCRIPT_DIR}/parent/pom.xml"
MAVEN_BASE="https://repo1.maven.org/maven2"

UPDATE=false
while getopts "u" opt; do
    case $opt in
        u) UPDATE=true ;;
        *) echo "Usage: $0 [-u]" >&2; exit 1 ;;
    esac
done

if [[ ! -f "$POM_FILE" ]]; then
    echo "Error: $POM_FILE not found" >&2
    exit 1
fi

# name|property|maven_path|pom_artifact_id
ARTIFACTS=(
    "quarkus-platform|quarkus.platform.version|io/quarkus/platform/quarkus-bom|quarkus-bom"
    "quarkus-operator-sdk|quarkus-operator-sdk.version|io/quarkiverse/operatorsdk/quarkus-operator-sdk-parent|quarkus-operator-sdk-parent"
    "quarkus-helm|quarkus-helm.version|io/quarkiverse/helm/quarkus-helm-parent|quarkus-helm-parent"
    "quarkus-mcp-server|quarkus-mcp-server.version|io/quarkiverse/mcp/quarkus-mcp-server-parent|quarkus-mcp-server-parent"
    "quarkus-oidc-proxy|quarkus-oidc-proxy.version|io/quarkiverse/oidc-proxy/quarkus-oidc-proxy-parent|quarkus-oidc-proxy-parent"
    "quarkus-infinispan-embedded|quarkus-infinispan-embedded.version|io/quarkiverse/infinispan/quarkus-infinispan-embedded-parent|quarkus-infinispan-embedded-parent"
)

get_latest_stable_version() {
    local path="$1"
    local url="${MAVEN_BASE}/${path}/maven-metadata.xml"
    local xml
    xml=$(curl -sf "$url") || { echo "FETCH_ERROR"; return; }

    local version
    version=$(echo "$xml" | grep -oP '<release>\K[^<]+' | head -1)
    if [[ -z "$version" ]]; then
        version=$(echo "$xml" | grep -oP '<latest>\K[^<]+' | head -1)
    fi

    if [[ "$version" =~ (Alpha|Beta|CR|RC|SNAPSHOT) ]]; then
        version=$(echo "$xml" | grep -oP '<version>\K[^<]+' | grep -vE '(Alpha|Beta|CR|RC|SNAPSHOT)' | sort -V | tail -1)
    fi

    echo "$version"
}

get_quarkus_version() {
    local path="$1"
    local artifact_id="$2"
    local version="$3"

    if [[ "$artifact_id" == "quarkus-bom" ]]; then
        echo "$version"
        return
    fi

    local pom_url="${MAVEN_BASE}/${path}/${version}/${artifact_id}-${version}.pom"
    local pom
    pom=$(curl -sf "$pom_url") || { echo "FETCH_ERROR"; return; }

    echo "$pom" | grep -oP '<quarkus\.version>\K[^<]+' | head -1
}

get_current_version() {
    local property="$1"
    local escaped="${property//./\\.}"
    grep -oP "<${escaped}>"'\K[^<]+' "$POM_FILE" | head -1
}

declare -a NAMES=()
declare -a CURRENT=()
declare -a CURRENT_QUARKUS=()
declare -a LATEST=()
declare -a LATEST_QUARKUS=()

echo "Fetching versions from Maven Central..."
echo ""

for entry in "${ARTIFACTS[@]}"; do
    IFS='|' read -r name property path artifact_id <<< "$entry"

    current=$(get_current_version "$property")

    current_qv=$(get_quarkus_version "$path" "$artifact_id" "$current")
    if [[ "$current_qv" == "FETCH_ERROR" || -z "$current_qv" ]]; then
        echo "Warning: failed to fetch quarkus version for $name $current" >&2
        current_qv="?"
    fi

    latest=$(get_latest_stable_version "$path")

    if [[ "$latest" == "FETCH_ERROR" ]]; then
        echo "Warning: failed to fetch metadata for $name" >&2
        latest="?"
        latest_qv="?"
    else
        latest_qv=$(get_quarkus_version "$path" "$artifact_id" "$latest")
        if [[ "$latest_qv" == "FETCH_ERROR" || -z "$latest_qv" ]]; then
            echo "Warning: failed to fetch quarkus version for $name $latest" >&2
            latest_qv="?"
        fi
    fi

    NAMES+=("$name")
    CURRENT+=("$current")
    CURRENT_QUARKUS+=("$current_qv")
    LATEST+=("$latest")
    LATEST_QUARKUS+=("$latest_qv")
done

FMT="%-30s  %-15s  %-17s  |  %-15s  %-15s\n"
printf "$FMT" "Artifact" "Current" "Current Quarkus" "Latest" "Latest Quarkus"
printf "$FMT" "------------------------------" "---------------" "-----------------" "---------------" "---------------"
for i in "${!NAMES[@]}"; do
    printf "$FMT" "${NAMES[$i]}" "${CURRENT[$i]}" "${CURRENT_QUARKUS[$i]}" "${LATEST[$i]}" "${LATEST_QUARKUS[$i]}"
done
major_minor() {
    echo "$1" | grep -oP '^\d+\.\d+'
}

find_compatible_version() {
    local path="$1"
    local artifact_id="$2"
    local current_ver="$3"
    local target_mm="$4"

    local url="${MAVEN_BASE}/${path}/maven-metadata.xml"
    local xml
    xml=$(curl -sf "$url") || { echo "${current_ver}|?"; return; }

    local all_versions
    all_versions=$(echo "$xml" | grep -oP '<version>\K[^<]+' | \
        grep -vE '(Alpha|Beta|CR|RC|SNAPSHOT)' | sort -V)

    local after_current=false
    local candidates=()
    while IFS= read -r v; do
        if [[ "$after_current" == true ]]; then
            candidates+=("$v")
        fi
        if [[ "$v" == "$current_ver" ]]; then
            after_current=true
        fi
    done <<< "$all_versions"

    local count=0
    for ((idx=${#candidates[@]}-1; idx>=0; idx--)); do
        local v="${candidates[$idx]}"
        local qv
        qv=$(get_quarkus_version "$path" "$artifact_id" "$v")
        if [[ -z "$qv" || "$qv" == "FETCH_ERROR" ]]; then continue; fi
        if [[ "$(major_minor "$qv")" == "$target_mm" ]]; then
            echo "${v}|${qv}"
            return
        fi
        ((count++))
        if ((count >= 10)); then break; fi
    done

    echo "${current_ver}|?"
}

current_platform=""
for i in "${!NAMES[@]}"; do
    if [[ "${NAMES[$i]}" == "quarkus-platform" ]]; then
        current_platform="${CURRENT[$i]}"
        break
    fi
done
current_mm=$(major_minor "$current_platform")

quarkus_versions=""
for i in "${!NAMES[@]}"; do
    if [[ "${NAMES[$i]}" == "quarkus-platform" ]]; then continue; fi
    if [[ "${LATEST_QUARKUS[$i]}" == "?" ]]; then continue; fi
    quarkus_versions+="${LATEST_QUARKUS[$i]}"$'\n'
done

declare -a REC_VERSIONS=()
declare -a REC_QUARKUS=()
has_recommendation=false

if [[ -n "$quarkus_versions" ]]; then
    min_quarkus=$(echo "$quarkus_versions" | grep -v '^$' | sort -V | head -1)
    target_mm=$(major_minor "$min_quarkus")

    platform_xml=$(curl -sf "${MAVEN_BASE}/io/quarkus/platform/quarkus-bom/maven-metadata.xml") || platform_xml=""
    recommended_platform=""
    if [[ -n "$platform_xml" ]]; then
        recommended_platform=$(echo "$platform_xml" | grep -oP '<version>\K[^<]+' | \
            grep -vE '(Alpha|Beta|CR|RC|SNAPSHOT)' | \
            grep "^${target_mm}\." | sort -V | tail -1)
    fi

    if [[ -n "$recommended_platform" && "$recommended_platform" != "$current_platform" ]]; then
        has_recommendation=true
        echo ""
        echo "Latest quarkus-platform in ${target_mm}.x series: $recommended_platform"
        echo "Checking compatible artifact versions..."

        for i in "${!NAMES[@]}"; do
            if [[ "${NAMES[$i]}" == "quarkus-platform" ]]; then
                REC_VERSIONS+=("$recommended_platform")
                REC_QUARKUS+=("$recommended_platform")
                continue
            fi

            local_latest_qmm="?"
            if [[ "${LATEST_QUARKUS[$i]}" != "?" ]]; then
                local_latest_qmm=$(major_minor "${LATEST_QUARKUS[$i]}")
            fi

            if [[ "$local_latest_qmm" == "$target_mm" ]]; then
                REC_VERSIONS+=("${LATEST[$i]}")
                REC_QUARKUS+=("${LATEST_QUARKUS[$i]}")
            else
                IFS='|' read -r _ _ path artifact_id <<< "${ARTIFACTS[$i]}"
                result=$(find_compatible_version "$path" "$artifact_id" "${CURRENT[$i]}" "$target_mm")
                IFS='|' read -r rec_v rec_qv <<< "$result"
                if [[ "$rec_v" == "${CURRENT[$i]}" && "$rec_qv" == "?" ]]; then
                    rec_qv="${CURRENT_QUARKUS[$i]}"
                fi
                REC_VERSIONS+=("$rec_v")
                REC_QUARKUS+=("$rec_qv")
            fi
        done

        echo ""
        REC_FMT="%-30s  %-15s  %-15s\n"
        printf "$REC_FMT" "Artifact" "Version" "Quarkus"
        printf "$REC_FMT" "------------------------------" "---------------" "---------------"
        for i in "${!NAMES[@]}"; do
            printf "$REC_FMT" "${NAMES[$i]}" "${REC_VERSIONS[$i]}" "${REC_QUARKUS[$i]}"
        done
    else
        echo ""
        echo "Quarkus platform $current_platform is already at the latest ${target_mm}.x release."
        echo "No update needed."
    fi
else
    echo ""
    echo "Warning: could not determine extension quarkus versions" >&2
fi

rec_operator_framework=""
if [[ "$has_recommendation" == true ]]; then
    for i in "${!NAMES[@]}"; do
        if [[ "${NAMES[$i]}" == "quarkus-operator-sdk" && "${REC_VERSIONS[$i]}" != "${CURRENT[$i]}" ]]; then
            rec_sdk_ver="${REC_VERSIONS[$i]}"
            sdk_pom_url="${MAVEN_BASE}/io/quarkiverse/operatorsdk/quarkus-operator-sdk-parent/${rec_sdk_ver}/quarkus-operator-sdk-parent-${rec_sdk_ver}.pom"
            sdk_pom=$(curl -sf "$sdk_pom_url") || sdk_pom=""
            if [[ -n "$sdk_pom" ]]; then
                josdk_ver=$(echo "$sdk_pom" | grep -oP '<java-operator-sdk\.version>\K[^<]+' | head -1)
                current_of=$(get_current_version "operator-framework.version")
                if [[ -n "$josdk_ver" && "$josdk_ver" != "$current_of" ]]; then
                    rec_operator_framework="$josdk_ver"
                    echo ""
                    echo "operator-framework.version: ${current_of} -> ${josdk_ver} (from quarkus-operator-sdk ${rec_sdk_ver})"
                else
                    echo ""
                    echo "operator-framework.version: ${current_of} (compatible with quarkus-operator-sdk ${rec_sdk_ver})"
                fi
            fi
            break
        fi
    done
fi

if [[ "$UPDATE" == true ]]; then
    if [[ "$has_recommendation" != true ]]; then
        echo ""
        echo "Nothing to update."
    else
        echo ""
        echo "Updating $POM_FILE..."

        for i in "${!NAMES[@]}"; do
            IFS='|' read -r _ property _ _ <<< "${ARTIFACTS[$i]}"
            escaped="${property//./\\.}"
            target="${REC_VERSIONS[$i]}"

            if [[ "$target" == "${CURRENT[$i]}" ]]; then
                echo "  ${property} unchanged (${target})"
                continue
            fi

            sed -i "s|<${escaped}>[^<]*</${escaped}>|<${property}>${target}</${property}>|" "$POM_FILE"
            echo "  ${property} -> ${target}"
        done

        if [[ -n "$rec_operator_framework" ]]; then
            sed -i "s|<operator-framework\.version>[^<]*</operator-framework\.version>|<operator-framework.version>${rec_operator_framework}</operator-framework.version>|" "$POM_FILE"
            echo "  operator-framework.version -> ${rec_operator_framework}"
        fi

        echo "Done."
    fi
fi
