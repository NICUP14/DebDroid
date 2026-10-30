#!/bin/sh
# check-functions.sh: Finds calls to functions a script cannot actually see
#
# A shell does not complain about a missing function until the line runs, and
# "cmd 2> /dev/null" hides even that. A call to a function defined in a file the
# script never sources therefore fails silently, returning non-zero, which an
# "if" treats as a plain false. That is how a guard meant to refuse an action
# ends up permitting it.
#
# This finds those calls by listing the functions each script can reach, from
# itself and from the files it sources, and flagging any project function it
# calls from outside that set.
#
# NOTE: Reachability is decided by which debdroid_*.sh basenames a script
#       mentions, not by parsing its "." lines, because the manager sources
#       through a loop and has no literal path to read. That is deliberately
#       permissive: a script that merely names a file, as the uninstaller does
#       when listing what to delete, is credited with its functions. It can
#       therefore miss a case, but it does not invent one, so a failure here is
#       always worth acting on.
#
# Usage: sh tools/check-functions.sh

failed=0

# The files every entry point is allowed to assume
SHARED="debdroid/debdroid_ui.sh debdroid/debdroid_lib.sh"

# Collects every function name defined across the project, so a call can be
# told apart from an ordinary external command
ALL_FUNCTIONS=$(grep -hE '^[a-z_][a-z0-9_]*\(\)' \
    debdroid/*.sh command/*.sh tools/*.sh tests/*.sh install.sh 2>/dev/null |
    sed 's/().*//' | sort -u)

echo ":: Checking function visibility"

for script in debdroid/*.sh command/*.sh install.sh; do
    [ -f "$script" ] || continue
    name=${script##*/}

    # Functions defined in the script itself
    visible=$(grep -hE '^[a-z_][a-z0-9_]*\(\)' "$script" 2>/dev/null |
        sed 's/().*//')

    # Plus those from every debdroid file it names
    # NOTE: Matched by basename anywhere in the file rather than by parsing the
    #       "." lines, because the manager sources through a loop over a list of
    #       filenames, so there is no literal path on the "." line to read.
    for candidate in debdroid/debdroid_*.sh; do
        [ -f "$candidate" ] || continue
        sourced=${candidate##*/}
        [ "$sourced" = "$name" ] && continue
        grep -q "$sourced" "$script" 2>/dev/null || continue

        visible="$visible
$(grep -hE '^[a-z_][a-z0-9_]*\(\)' "$candidate" | sed 's/().*//')"
    done

    # The two shared libraries are sourced together by every entry point, and
    # debdroid_lib.sh documents that it reports through debdroid_ui.sh, so each
    # may rely on the other
    case "$name" in
        debdroid_ui.sh|debdroid_lib.sh)
            for shared in $SHARED; do
                visible="$visible
$(grep -hE '^[a-z_][a-z0-9_]*\(\)' "$shared" | sed 's/().*//')"
            done
            ;;
    esac

    visible=$(echo "$visible" | sort -u | grep -v '^$')

    # Every project function the script calls has to be in that set
    missing=
    for func in $ALL_FUNCTIONS; do
        # Does the script call it? A call is the name at the start of a command
        if ! grep -qE "(^|[;&|]|^\s+|if |then |else |! |\\\$\()\s*$func( |$|\"|;|\))" \
                "$script" 2>/dev/null; then
            continue
        fi

        # Its own definition is not a call
        if [ "$(grep -cE "^$func\(\)" "$script")" -gt 0 ]; then
            continue
        fi

        case "
$visible
" in
            *"
$func
"*) ;;
            *) missing="$missing $func" ;;
        esac
    done

    if [ -n "$missing" ]; then
        echo "FAIL $name" >&2
        for func in $missing; do
            echo "     calls \"$func\", which it cannot see" >&2
        done
        echo "     source the file that defines it, or move it into" >&2
        echo "     debdroid/debdroid_lib.sh" >&2
        failed=$((failed + 1))
    else
        echo "  ok  $name"
    fi
done

echo
if [ "$failed" -eq 0 ]; then
    echo "functions: every call resolves"
    exit 0
fi

echo "functions: $failed script(s) call something they cannot see" >&2
exit 1
