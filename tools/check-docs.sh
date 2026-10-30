#!/bin/sh
# check-docs.sh: Checks the documentation for broken links and stray jargon
#
# The docs are split into three tiers for three audiences, and the two things
# that erode that split are links rotting after a rename and implementation
# vocabulary creeping into the guides. Both are checked here.
#
# Usage: sh tools/check-docs.sh

failed=0

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

echo ":: Checking internal links"

links=0
for file in README.md THIRD_PARTY.md NOTICE TODO.md CHANGELOG.md \
    docs/*.md docs/guide/*.md docs/reference/*.md; do
    [ -f "$file" ] || continue
    directory=${file%/*}
    [ "$directory" = "$file" ] && directory=.

    # Pulls the target out of every markdown link, dropping any anchor
    targets=$(sed -n 's/.*](\([^)#]*\)[^)]*).*/\1/p' "$file" |
        grep -v '^https\?://' | grep -v '^mailto:' | grep -v '^#' | grep -v '^$')

    for target in $targets; do
        links=$((links + 1))

        # Resolves the link against the file's own directory
        case "$target" in
            /*) resolved=".$target" ;;
            *)  resolved="$directory/$target" ;;
        esac

        if [ ! -e "$resolved" ]; then
            fail "$file" "broken link: $target" "resolved to: $resolved"
        fi
    done
done

echo "  checked $links link(s)"

echo
echo ":: Checking the old documentation paths still redirect"

# NOTE: These were linked from the public README and from forum posts before the
#       restructure, so they have to keep resolving rather than 404.
for stub in docs/Install.md docs/Quickstart.md docs/Troubleshooting.md; do
    if [ ! -f "$stub" ]; then
        fail "$stub" "the redirect stub is missing" \
            "external links still point here"
    elif ! grep -q 'moved' "$stub"; then
        fail "$stub" "the stub does not say the page moved"
    fi
done

echo
echo ":: Checking the guides stay free of implementation vocabulary"

# NOTE: The guide tier is for readers who should never need to know how
#       DebDroid works internally. A hit here is either a word to replace or a
#       concept that needs a plain-language gloss plus a reference link.
JARGON='chroot mount namespace overlayfs loop device lock file'

for file in docs/guide/*.md; do
    [ -f "$file" ] || continue

    for term in namespace overlayfs chroot; do
        if hits=$(grep -n -i "$term" "$file"); then
            fail "$file" "guide-tier page uses \"$term\"" "$hits"
        fi
    done
done

echo "  vocabulary to keep out of guides: $JARGON"

echo
if [ "$failed" -eq 0 ]; then
    echo "docs: ok"
    exit 0
fi

echo "docs: $failed problem(s) found" >&2
exit 1
