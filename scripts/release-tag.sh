#!/usr/bin/env bash
# Tag and push a MapSync release after validating the build locally.
#
# Usage:
#     scripts/release-tag.sh v26.1.2.12
#
# Behaviour:
#   1. Validates the tag name (must start with v<digits>).
#   2. Refuses to run if the tag already exists locally or on origin.
#   3. Refuses to run if the working tree has uncommitted changes.
#   4. Warns if HEAD isn't on origin/main (you might be tagging an
#      orphaned commit).
#   5. Runs `./gradlew build` in mapsync-mod/. Aborts on failure.
#   6. Creates an annotated tag on HEAD and pushes it to origin.
#
# The release workflow on GitHub takes it from there: builds again on
# fresh CI, publishes the jar to the GitHub Release, and pushes a
# Modrinth version if MODRINTH_PROJECT_ID is set.

set -euo pipefail

usage() {
	cat <<EOF
Usage: $0 <tag-name>

Examples:
  $0 v26.1.2.12          # stable release
  $0 v26.1.2.12-rc1      # release candidate (workflow tags as 'beta' on Modrinth)
  $0 v26.1.2.12-alpha1   # alpha (workflow tags as 'alpha' on Modrinth)
EOF
}

if [[ $# -ne 1 ]]; then
	usage
	exit 2
fi
case "$1" in
	-h|--help) usage; exit 0 ;;
esac

TAG="$1"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 1. Tag-name shape.
if [[ ! "$TAG" =~ ^v[0-9]+(\.[0-9]+)*([.-][A-Za-z0-9]+)*$ ]]; then
	echo "ERROR: tag must look like v<digits>… (got: $TAG)" >&2
	exit 2
fi

cd "$REPO_ROOT"

# 2. Tag uniqueness.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
	echo "ERROR: tag $TAG already exists locally. Delete it first if you really mean to re-tag:" >&2
	echo "    git tag -d $TAG && git push origin :refs/tags/$TAG" >&2
	exit 1
fi
if git ls-remote --tags origin "refs/tags/$TAG" 2>/dev/null | grep -q "refs/tags/$TAG\$"; then
	echo "ERROR: tag $TAG already exists on origin." >&2
	exit 1
fi

# 3. Working tree clean.
if ! git diff-index --quiet HEAD -- 2>/dev/null; then
	echo "ERROR: working tree has uncommitted changes. Commit or stash first." >&2
	echo "" >&2
	git status --short >&2
	exit 1
fi

# 4. HEAD vs origin/main sanity check.
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [[ "$CURRENT_BRANCH" != "main" ]]; then
	echo "WARNING: HEAD is on '$CURRENT_BRANCH', not 'main'. Continuing anyway."
fi
LOCAL_HEAD="$(git rev-parse HEAD)"
REMOTE_MAIN="$(git ls-remote origin main 2>/dev/null | awk '{print $1}' || true)"
if [[ -n "$REMOTE_MAIN" && "$LOCAL_HEAD" != "$REMOTE_MAIN" ]]; then
	echo "WARNING: HEAD ($LOCAL_HEAD) doesn't match origin/main ($REMOTE_MAIN)."
	echo "         The tag will point at a commit that may not be on origin yet."
	echo "         Make sure you've pushed your commits first."
fi

# 5. Local build — override mapsync_version with the tag (minus the
#    leading `v`) so the produced jar in `dist/` is named the same
#    way CI will name it.
VERSION="${TAG#v}"
echo ">>> Running local build (mapsync_version=${VERSION})..."
echo ">>> cd mapsync-mod && ./gradlew --no-daemon build -Pmapsync_version=${VERSION}"
(
	cd mapsync-mod
	./gradlew --no-daemon build -Pmapsync_version="${VERSION}"
)
echo ">>> Build OK."

# 6. Create + push tag.
echo ">>> Creating tag $TAG on $LOCAL_HEAD..."
git tag -a "$TAG" -m "MapSync ${TAG#v} — release tag"
echo ">>> Pushing tag to origin..."
git push origin "$TAG"

echo ""
echo ">>> Done. Watch the release workflow at:"
echo "    https://github.com/Pytonballoon810/map-sync/actions"
