#!/usr/bin/env bash
# Imports Taffy's flexbox layout test cases as data for core's TaffyFlexTest.
#
# Usage: scripts/import-taffy-fixtures.sh [<commit>]
#
#   <commit>  the Taffy commit, branch or tag to take the test cases from; default: main
#
# Taffy (https://github.com/DioxusLabs/taffy, MIT) writes its test cases as HTML files under
# test_fixtures/, renders each in Chrome in four variants (border-box or content-box, ltr or rtl)
# and commits what Chrome reports as one XML file per variant under tests/xml/. Each file holds
# the styled tree and the expected rectangles. This script joins the files of tests/xml/flex/
# into core/src/test/resources/taffy/flex.xml, unchanged, under a root element that records the
# commit, and copies Taffy's licence beside it. No browser is needed.
#
# After an import, run `mvn -pl core verify`. A new case that uses a style property the test does
# not know fails with the name of that property.
set -euo pipefail

commit="${1:-main}"
out="$(cd "$(dirname "$0")/.." && pwd)/core/src/test/resources/taffy"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone --quiet --filter=blob:none --no-checkout https://github.com/DioxusLabs/taffy "$work"
git -C "$work" checkout --quiet "$commit"
sha="$(git -C "$work" rev-parse HEAD)"

mkdir -p "$out"
{
  echo "<tests source=\"https://github.com/DioxusLabs/taffy\" path=\"tests/xml/flex\" commit=\"$sha\">"
  # Sorted by the C locale, so that the order does not depend on the machine.
  find "$work/tests/xml/flex" -name '*.xml' -print0 | LC_ALL=C sort -z | xargs -0 cat
  echo "</tests>"
} > "$out/flex.xml"
cp "$work/LICENSE" "$out/LICENSE"

echo "Imported $(grep -c '<test ' "$out/flex.xml") test cases from Taffy $sha"
