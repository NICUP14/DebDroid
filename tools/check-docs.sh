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
for file in README.md THIRD_PARTY.md NOTICE TODO.md CHANGELOG.md AGENTS.md \
    docs/*.md docs/guide/*.md docs/guide/recipes/*.md docs/reference/*.md; do
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
echo ":: Checking there is one index page, not a tree of them"

# NOTE: docs/README.md is the only index. The per-tier indexes and the redirect
#       stubs that used to sit beside it were pages whose whole content was
#       links to other pages, which is a click the reader should not have to
#       spend. Anything new goes into docs/README.md rather than beside it.
if [ ! -f docs/README.md ]; then
    fail "docs/README.md" "the one index page is missing"
fi

for gone in docs/Install.md docs/Quickstart.md docs/Troubleshooting.md \
    docs/guide/README.md docs/reference/README.md; do
    if [ -f "$gone" ]; then
        fail "$gone" "this index page was removed deliberately" \
            "link the page itself from docs/README.md instead"
    fi
done

echo
echo ":: Checking the guides stay free of implementation vocabulary"

# NOTE: The guide tier is for readers who should never need to know how
#       DebDroid works internally. A hit here is either a word to replace or a
#       concept that needs a plain-language gloss plus a reference link.
JARGON='chroot mount namespace overlayfs loop device lock file'

for file in docs/guide/*.md docs/guide/recipes/*.md; do
    [ -f "$file" ] || continue

    for term in namespace overlayfs chroot; do
        if hits=$(grep -n -i "$term" "$file"); then
            fail "$file" "guide-tier page uses \"$term\"" "$hits"
        fi
    done
done

echo "  vocabulary to keep out of guides: $JARGON"

echo ":: Checking the prose uses no em dashes"

# NOTE: A house style rather than a correctness rule: the em dash reads as
#       filler here, and a full stop, a colon or parentheses is almost always
#       clearer. Checked because an unenforced convention drifts back one
#       sentence at a time.
# NOTE: Globbed by depth rather than listed by name, so a markdown file added
#       anywhere is covered without anyone remembering to extend a list. A
#       leading "*" does not match a dotfile, so .git is skipped for free.
for file in NOTICE *.md */*.md */*/*.md */*/*/*.md; do
    [ -f "$file" ] || continue

    # out/ holds built release artifacts, not project prose
    case "$file" in
        out/*) continue ;;
    esac

    if hits=$(grep -n '—' "$file"); then
        fail "$file" "uses an em dash" "$hits" \
            "rewrite with a full stop, a colon or parentheses"
    fi
done

echo
if [ "$failed" -eq 0 ]; then
    echo "docs: ok"
    exit 0
fi

echo "docs: $failed problem(s) found" >&2
exit 1
