#!/usr/bin/env bash
#
# Update Gradle / Android / Kotlin / library versions in-place to the latest
# stable releases. Run from anywhere:
#
#   scripts/update-deps.sh           # apply updates
#   scripts/update-deps.sh --dry-run # just print what would change
#
# Targets:
#   gradle/libs.versions.toml        (all library + plugin versions)
#   gradle/wrapper/gradle-wrapper.properties
#
# If a major-version bump lands that needs a hand-written refactor, set a
# major-version pin below (see KTOR_MAJOR for the pattern).

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
CATALOG="${REPO_ROOT}/gradle/libs.versions.toml"
WRAPPER_PROPS="${REPO_ROOT}/gradle/wrapper/gradle-wrapper.properties"

DRY_RUN=0
[[ "${1:-}" == "--dry-run" ]] && DRY_RUN=1

# Example pin (empty = no pin, take latest). e.g. KTOR_MAJOR="3" to cap at 3.x.
KTOR_MAJOR=""

GOOGLE_MAVEN="https://dl.google.com/android/maven2"
CENTRAL="https://repo1.maven.org/maven2"

latest_stable() {
    local url="$1" major="${2:-}"
    local versions
    versions="$(curl -fsSL "$url" \
        | grep -oE '<version>[^<]+</version>' \
        | sed -E 's#</?version>##g' \
        | grep -viE 'alpha|beta|rc|dev|preview|snapshot|-m[0-9]')"
    if [[ -n "$major" ]]; then
        versions="$(echo "$versions" | grep -E "^${major}\\.")"
    fi
    echo "$versions" | sort -V | tail -n1
}

latest_gradle() {
    curl -fsSL https://services.gradle.org/versions/current \
        | grep -oE '"version"[[:space:]]*:[[:space:]]*"[^"]+"' \
        | head -n1 \
        | sed -E 's/.*"([^"]+)"/\1/'
}

apply_sed() {
    local file="$1" expr="$2"
    if [[ "$DRY_RUN" -eq 1 ]]; then
        local before after
        before="$(cat "$file")"
        after="$(sed -E "$expr" "$file")"
        if [[ "$before" != "$after" ]]; then
            diff <(echo "$before") <(echo "$after") || true
        fi
    else
        sed -i -E "$expr" "$file"
    fi
}

# Bump a version line in libs.versions.toml: `key = "X.Y.Z"` under [versions].
bump_catalog() {
    local key="$1" new="$2"
    apply_sed "$CATALOG" "s#^(${key}[[:space:]]*=[[:space:]]*\")[^\"]+\"#\\1${new}\"#"
}

bump_wrapper() {
    local new="$1"
    apply_sed "$WRAPPER_PROPS" \
        "s#(distributionUrl=https\\\\://services\\.gradle\\.org/distributions/gradle-)[^-]+(-bin\\.zip)#\\1${new}\\2#"
}

echo "Querying latest stable versions..."
AGP=$(latest_stable "${GOOGLE_MAVEN}/com/android/tools/build/gradle/maven-metadata.xml")
KOTLIN=$(latest_stable "${CENTRAL}/org/jetbrains/kotlin/kotlin-gradle-plugin/maven-metadata.xml")
COMPOSE_BOM=$(latest_stable "${GOOGLE_MAVEN}/androidx/compose/compose-bom/maven-metadata.xml")
ACTIVITY_COMPOSE=$(latest_stable "${GOOGLE_MAVEN}/androidx/activity/activity-compose/maven-metadata.xml")
LIFECYCLE=$(latest_stable "${GOOGLE_MAVEN}/androidx/lifecycle/lifecycle-runtime-compose/maven-metadata.xml")
COROUTINES=$(latest_stable "${CENTRAL}/org/jetbrains/kotlinx/kotlinx-coroutines-android/maven-metadata.xml")
KTOR=$(latest_stable "${CENTRAL}/io/ktor/ktor-server-core/maven-metadata.xml" "$KTOR_MAJOR")
GRADLE=$(latest_gradle)

printf '  %-20s %s\n'  "Gradle"           "$GRADLE"
printf '  %-20s %s\n'  "AGP"              "$AGP"
printf '  %-20s %s\n'  "Kotlin"           "$KOTLIN"
printf '  %-20s %s\n'  "Compose BOM"      "$COMPOSE_BOM"
printf '  %-20s %s\n'  "activity-compose" "$ACTIVITY_COMPOSE"
printf '  %-20s %s\n'  "lifecycle"        "$LIFECYCLE"
printf '  %-20s %s\n'  "coroutines"       "$COROUTINES"
if [[ -n "$KTOR_MAJOR" ]]; then
    printf '  %-20s %s (pinned to %s.x)\n' "Ktor" "$KTOR" "$KTOR_MAJOR"
else
    printf '  %-20s %s\n' "Ktor" "$KTOR"
fi
echo

for v in "$AGP" "$KOTLIN" "$COMPOSE_BOM" "$ACTIVITY_COMPOSE" "$LIFECYCLE" \
         "$COROUTINES" "$KTOR" "$GRADLE"; do
    if [[ -z "$v" ]]; then
        echo "ERROR: failed to resolve one or more versions; aborting." >&2
        exit 1
    fi
done

if [[ "$DRY_RUN" -eq 1 ]]; then
    echo "--dry-run: showing diffs only, not writing files"
    echo
fi

bump_catalog "agp"             "$AGP"
bump_catalog "kotlin"          "$KOTLIN"
bump_catalog "composeBom"      "$COMPOSE_BOM"
bump_catalog "activityCompose" "$ACTIVITY_COMPOSE"
bump_catalog "lifecycle"       "$LIFECYCLE"
bump_catalog "coroutines"      "$COROUTINES"
bump_catalog "ktor"            "$KTOR"

bump_wrapper "$GRADLE"

if [[ "$DRY_RUN" -eq 0 ]]; then
    echo "Done. Review with 'git diff' and rebuild with ./gradlew assembleDebug."
fi
