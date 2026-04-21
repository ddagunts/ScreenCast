#!/usr/bin/env bash
# Cut a new ScreenCast release: bump versionCode/versionName, add the build
# entry + CurrentVersion in .fdroid.yml, write the fastlane changelog file,
# verify the build + tests, then commit everything tracked and tag v<version>.
#
# Before running:
#   1. Make your code changes for the release.
#   2. Edit CHANGELOG.md to add a new "## [<version>]" section at the top,
#      plus the matching compare-link line in the footer.
#   3. Run this script. It reads the fastlane short changelog from stdin —
#      a handful of bullet points, ≤500 chars.
#
# Usage:
#   scripts/release.sh <version>              # reads fastlane text from stdin
#   scripts/release.sh <version> <file>       # reads fastlane text from file
#
# Examples:
#   scripts/release.sh 0.5.4 <<'EOF'
#   • Fix: casting fell back to VPN IP with some VPNs
#   • Defaults tweaked: 10s sync interval, 30ms drift threshold
#   EOF
#
#   scripts/release.sh 0.5.4 /tmp/changelog.txt

set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
    echo "Usage: $0 <version> [fastlane-changelog-file]" >&2
    exit 1
fi

VERSION_NAME="$1"
TAG="v${VERSION_NAME}"

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"

# Gradle's Java toolchain picks whatever it finds first; on this box that's
# a headless JRE (no javac), which fails with "does not provide the required
# capabilities: [JAVA_COMPILER]". Honor JAVA_HOME if already set, otherwise
# probe well-known Android Studio install paths for a bundled JBR with javac.
if [ -z "${JAVA_HOME:-}" ]; then
    for candidate in \
        "${HOME}/Downloads/android-studio-panda3-patch1-linux/android-studio/jbr" \
        "${HOME}/Android/android-studio/jbr" \
        "/opt/android-studio/jbr" \
        "/usr/lib/jvm/java-21-openjdk-amd64" \
        "/usr/lib/jvm/java-17-openjdk-amd64"; do
        if [ -x "${candidate}/bin/javac" ]; then
            export JAVA_HOME="${candidate}"
            break
        fi
    done
fi
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME}/bin/javac" ]; then
    echo "error: couldn't find a JDK with javac. Set JAVA_HOME to a JDK (not a JRE)." >&2
    exit 1
fi
echo "JAVA_HOME:      ${JAVA_HOME}"

GRADLE="app/build.gradle.kts"
FDROID=".fdroid.yml"
CHANGELOG="CHANGELOG.md"

# --- Pre-flight ------------------------------------------------------------

if git rev-parse "${TAG}" >/dev/null 2>&1; then
    echo "error: tag ${TAG} already exists" >&2
    exit 1
fi

if ! grep -qE "^## \[${VERSION_NAME}\]" "${CHANGELOG}"; then
    echo "error: ${CHANGELOG} has no '## [${VERSION_NAME}]' section" >&2
    echo "       Add the release notes there before running this script." >&2
    exit 1
fi

CURRENT_CODE="$(grep -oE 'versionCode = [0-9]+' "${GRADLE}" | grep -oE '[0-9]+' | head -1)"
if [ -z "${CURRENT_CODE:-}" ]; then
    echo "error: couldn't parse versionCode from ${GRADLE}" >&2
    exit 1
fi
NEXT_CODE=$((CURRENT_CODE + 1))

CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
echo "Branch:         ${CURRENT_BRANCH}"
echo "versionName:    -> ${VERSION_NAME}"
echo "versionCode:    ${CURRENT_CODE} -> ${NEXT_CODE}"
echo

# --- Fastlane short changelog ---------------------------------------------

FASTLANE_FILE="fastlane/metadata/android/en-US/changelogs/${NEXT_CODE}.txt"

if [ "$#" -eq 2 ]; then
    cp "$2" "${FASTLANE_FILE}"
else
    if [ -t 0 ]; then
        echo "Paste the F-Droid changelog (bullet list, end with Ctrl-D):" >&2
    fi
    cat > "${FASTLANE_FILE}"
fi

if [ ! -s "${FASTLANE_FILE}" ]; then
    echo "error: fastlane changelog is empty" >&2
    rm -f "${FASTLANE_FILE}"
    exit 1
fi

# --- Bump versionCode / versionName in build.gradle.kts -------------------

sed -i -E \
    -e "s/versionCode = ${CURRENT_CODE}/versionCode = ${NEXT_CODE}/" \
    -e "s/versionName = \"[^\"]+\"/versionName = \"${VERSION_NAME}\"/" \
    "${GRADLE}"

# --- .fdroid.yml: insert Builds entry + bump CurrentVersion ---------------

awk -v name="${VERSION_NAME}" -v code="${NEXT_CODE}" '
/^AutoUpdateMode:/ {
    printf "  - versionName: %c%s%c\n", 39, name, 39
    printf "    versionCode: %s\n", code
    printf "    commit: v%s\n", name
    printf "    subdir: app\n"
    printf "    gradle:\n"
    printf "      - yes\n"
    printf "\n"
}
/^CurrentVersion:/    { printf "CurrentVersion: %c%s%c\n", 39, name, 39; next }
/^CurrentVersionCode:/ { printf "CurrentVersionCode: %s\n", code; next }
{ print }
' "${FDROID}" > "${FDROID}.tmp" && mv "${FDROID}.tmp" "${FDROID}"

# --- Verify build + tests --------------------------------------------------

echo "Running :app:assembleDebug + :app:testDebugUnitTest..."
./gradlew :app:assembleDebug :app:testDebugUnitTest >/dev/null

# --- Commit + tag ----------------------------------------------------------

# Stage everything tracked that's been modified, plus the new fastlane file.
git add -u
git add "${FASTLANE_FILE}"

git commit -m "${VERSION_NAME}"
git tag "${TAG}"

echo
echo "Released ${VERSION_NAME} (code ${NEXT_CODE})."
echo "Push with: git push origin ${CURRENT_BRANCH} ${TAG}"
