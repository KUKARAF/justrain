#!/usr/bin/env bash
# Tag the current commit as <shortsha>.<MAJOR>.<MINOR> and push the tag, which
# triggers the signed-release CI (a plain `git push` triggers a prerelease).
#
#   .tools/tag_and_push.sh          # keep MAJOR.MINOR, just refresh the short-sha
#   .tools/tag_and_push.sh minor    # MINOR + 1
#   .tools/tag_and_push.sh major    # MAJOR + 1, MINOR reset to 0
#
# Version numbers are read from the most recent existing tag of this form.
set -euo pipefail

cd "$(git rev-parse --show-toplevel)"

bump="${1:-none}"
case "$bump" in
  none|minor|major) ;;
  *) echo "usage: $0 [minor|major]" >&2; exit 1 ;;
esac

# Most recent tag matching <anything>.<digits>.<digits>
last="$(git for-each-ref --sort=-creatordate --format='%(refname:short)' refs/tags \
        | grep -E '\.[0-9]+\.[0-9]+$' | head -n1 || true)"

if [[ -n "$last" ]]; then
  major="$((10#$(echo "$last" | awk -F. '{print $(NF-1)}')))"
  minor="$((10#$(echo "$last" | awk -F. '{print $NF}')))"
else
  # No prior version tag: start just below 0.1 so the first bump/none yields 00.01
  major=0
  minor=1
  bump="none"   # first tag is 00.01 regardless of arg
fi

case "$bump" in
  minor) minor=$((minor + 1)) ;;
  major) major=$((major + 1)); minor=0 ;;
  none)  : ;;   # keep numbers, only the short-sha changes
esac

short_sha="$(git rev-parse --short HEAD)"
tag="$(printf '%s.%02d.%02d' "$short_sha" "$major" "$minor")"

echo "Previous tag : ${last:-<none>}"
echo "New tag      : $tag"

if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  echo "Tag $tag already exists; aborting." >&2
  exit 1
fi

git tag -a "$tag" -m "justrain $tag"
git push origin "$tag"
echo "Pushed $tag — the signed-release build will run in GitHub Actions."
