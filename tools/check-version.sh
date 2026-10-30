#!/bin/sh
# check-version.sh: Checks that every file naming a version agrees
#
# DEBDROID_VER in debdroid/debdroid_env.sh is the single source of truth. The
# version is repeated in a few places that matter, and a stale copy is worse
# than no copy: module.prop drives the module update check, and TODO.md is
# where the roadmap is tracked.
#
# Usage: sh tools/check-version.sh

failed=0

# Reports a mismatch and marks the run as failed
fail()
{
    echo "FAIL $1" >&2
    shift
    for detail in "$@"; do
        echo "     $detail" >&2
    done
    failed=$((failed + 1))
}

VERSION=$(sed -n "s/^export DEBDROID_VER='\(.*\)'$/\1/p" debdroid/debdroid_env.sh)

if [ -z "$VERSION" ]; then
    echo "Error: Could not read DEBDROID_VER from debdroid/debdroid_env.sh." >&2
    exit 1
fi

echo ":: Source of truth: DEBDROID_VER = $VERSION"

# The module's version drives its update check, so a stale value misreports
module_version=$(sed -n 's/^version=v//p' module/module.prop)
if [ "$module_version" != "$VERSION" ]; then
    fail "module/module.prop" \
        "version=v$module_version does not match DEBDROID_VER $VERSION"
else
    echo "  ok  module/module.prop"
fi

# versionCode has to increase for the module manager to offer an update, and is
# derived from the version so it cannot be forgotten: 1.2.6 becomes 10206
expected_code=$(echo "$VERSION" | awk -F. '{ printf "%d%02d%02d", $1, $2, $3 }')
module_code=$(sed -n 's/^versionCode=//p' module/module.prop)
if [ "$module_code" != "$expected_code" ]; then
    fail "module/module.prop" \
        "versionCode=$module_code, expected $expected_code for version $VERSION"
else
    echo "  ok  module/module.prop versionCode"
fi

# The roadmap has to name the version that is actually installed
if ! grep -q "^\*\*Current version: v$VERSION\*\*$" TODO.md; then
    fail "TODO.md" \
        "no \"**Current version: v$VERSION**\" line found" \
        "the roadmap tracks work per version, so this has to be updated too"
else
    echo "  ok  TODO.md current version"
fi

# A released version needs a section in the roadmap and an entry in the log
if ! grep -q "v$VERSION" TODO.md; then
    fail "TODO.md" "no section mentions v$VERSION"
else
    echo "  ok  TODO.md has a v$VERSION section"
fi

if ! grep -q "^## $VERSION$" CHANGELOG.md; then
    fail "CHANGELOG.md" "no \"## $VERSION\" section found"
else
    echo "  ok  CHANGELOG.md has a $VERSION section"
fi

# update.json is what the module manager polls
if [ -f module/update.json ]; then
    if ! grep -q "\"version\": \"v$VERSION\"" module/update.json; then
        fail "module/update.json" "version does not match DEBDROID_VER $VERSION"
    else
        echo "  ok  module/update.json"
    fi
fi

echo
if [ "$failed" -eq 0 ]; then
    echo "version: everything agrees on $VERSION"
    exit 0
fi

echo "version: $failed mismatch(es) found" >&2
exit 1
