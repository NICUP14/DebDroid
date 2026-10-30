#!/bin/sh
# verify-provenance.sh: Checks every bundled binary against provenance/manifest
#
# Run it from the repository root, or through "make provenance". CI runs it on
# every push, so a binary that is added, changed or left undocumented fails the
# build rather than quietly shipping.
#
# Usage: sh tools/verify-provenance.sh

MANIFEST=provenance/manifest

if [ ! -f "$MANIFEST" ]; then
    echo "Error: Missing the manifest: $MANIFEST" >&2
    exit 1
fi

failed=0
checked=0

# Reports a problem and marks the run as failed
fail()
{
    echo "FAIL $1" >&2
    shift
    for detail in "$@"; do
        echo "     $detail" >&2
    done
    failed=$((failed + 1))
}

echo ":: Verifying the recorded checksums"

# Reads the manifest, skipping comments and blank lines
# NOTE: The trailing notes field is read only so it does not spill into
#       "upstream", which would make the record look malformed.
# shellcheck disable=SC2034
while IFS='|' read -r path tier sha bytes license upstream notes; do
    case "$path" in
        '#'*|'') continue ;;
    esac

    # Trims the surrounding spaces the format uses for readability
    path=$(echo "$path" | sed 's/^ *//; s/ *$//')
    tier=$(echo "$tier" | sed 's/^ *//; s/ *$//')
    sha=$(echo "$sha" | sed 's/^ *//; s/ *$//')
    bytes=$(echo "$bytes" | sed 's/^ *//; s/ *$//')
    license=$(echo "$license" | sed 's/^ *//; s/ *$//')
    upstream=$(echo "$upstream" | sed 's/^ *//; s/ *$//')

    [ -n "$path" ] || continue
    checked=$((checked + 1))

    if [ ! -f "$path" ]; then
        fail "$path" "the manifest lists it, but the file is missing"
        continue
    fi

    # Every field has to be filled in, or the record documents nothing
    for field in tier sha license upstream; do
        eval "value=\$$field"
        if [ -z "$value" ]; then
            fail "$path" "the \"$field\" field is empty"
            continue 2
        fi
    done

    case "$tier" in
        A|B|C) ;;
        *) fail "$path" "unknown tier: \"$tier\", expected A, B or C" ; continue ;;
    esac

    actual_sha=$(sha256sum "$path" | cut -d ' ' -f 1)
    if [ "$actual_sha" != "$sha" ]; then
        fail "$path" "checksum mismatch" "recorded: $sha" "actual:   $actual_sha"
        continue
    fi

    actual_bytes=$(wc -c < "$path" | tr -d ' ')
    if [ "$actual_bytes" != "$bytes" ]; then
        fail "$path" "size mismatch" "recorded: $bytes" "actual:   $actual_bytes"
        continue
    fi

    echo "  ok  $path (tier $tier, $license)"
done < "$MANIFEST"

echo
echo ":: Looking for undocumented binaries"

# NOTE: This is the half that matters over time. Verifying what is listed is
#       easy; catching what someone forgot to list is what keeps tier C from
#       quietly refilling.
# NOTE: An unmatched glob stays literal, which the file check below discards.
for file in bin/* lib/* apk/*; do
    [ -f "$file" ] || continue

    # Text files in these directories are scripts, which are reviewable as source
    case "$file" in
        *.md|*.sh) continue ;;
    esac
    if head -c 2 "$file" 2>/dev/null | grep -q '#!'; then
        continue
    fi

    if ! grep -q "^$file *|" "$MANIFEST"; then
        fail "$file" "a binary with no manifest entry" \
            "add it to $MANIFEST, or delete it"
    fi
done

echo
if [ "$failed" -eq 0 ]; then
    echo "provenance: $checked file(s) verified, no undocumented binaries"
    exit 0
fi

echo "provenance: $failed problem(s) found" >&2
exit 1
