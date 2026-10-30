#!/system/bin/sh
# assert.sh: Minimal POSIX test helpers shared by the host and device suites
#
# Counters are globals, as POSIX sh has no local scope and running a check in a
# subshell would discard its result. Every helper prints exactly one line.

TEST_PASS=0
TEST_FAIL=0
TEST_SKIP=0

# Prints through busybox when available, as very old android shells lack printf
test_printf()
{
    if [ -x "$BUSYBOX" ]; then
        $BUSYBOX printf "$@"
    else
        printf "$@"
    fi
}

# Enables colored output when writing to a terminal (disabled by NO_COLOR)
test_setup_colors()
{
    if [ -t 1 ] && [ -z "$NO_COLOR" ]; then
        TEST_GREEN=$(test_printf '\033[32m')
        TEST_RED=$(test_printf '\033[1;31m')
        TEST_YELLOW=$(test_printf '\033[33m')
        TEST_BOLD=$(test_printf '\033[1m')
        TEST_RESET=$(test_printf '\033[0m')
    fi
}

# Prints a section header
t_section()
{
    echo
    echo "${TEST_BOLD}:: $1${TEST_RESET}"
}

# Records a passing check
t_ok()
{
    TEST_PASS=$((TEST_PASS + 1))
    echo " ${TEST_GREEN}PASS${TEST_RESET} $1"
}

# Records a failing check, printing the indented details that follow
t_no()
{
    TEST_FAIL=$((TEST_FAIL + 1))
    echo " ${TEST_RED}FAIL${TEST_RESET} $1"
    shift
    for detail in "$@"; do
        [ -n "$detail" ] || continue
        echo "$detail" | while IFS= read -r line; do
            echo "      $line"
        done
    done
}

# Records a check that could not run, which is not a failure
t_skip()
{
    TEST_SKIP=$((TEST_SKIP + 1))
    echo " ${TEST_YELLOW}SKIP${TEST_RESET} $1"
    [ -n "$2" ] && echo "      $2"
}

# Compares two values
# Usage: t_eq <label> <expected> <actual>
t_eq()
{
    if [ "$2" = "$3" ]; then
        t_ok "$1"
    else
        t_no "$1" "expected: $2" "actual:   $3"
    fi
}

# Checks that a command succeeds
# Usage: t_true <label> <command...>
t_true()
{
    label=$1
    shift
    if t_output=$("$@" 2>&1); then
        t_ok "$label"
    else
        t_no "$label" "$t_output"
    fi
}

# Checks that a command fails
# Usage: t_false <label> <command...>
t_false()
{
    label=$1
    shift
    if t_output=$("$@" 2>&1); then
        t_no "$label" "the command unexpectedly succeeded" "$t_output"
    else
        t_ok "$label"
    fi
}

# Checks that a file exists and is a regular file
t_file()
{
    if [ -f "$2" ]; then
        t_ok "$1"
    else
        t_no "$1" "missing file: $2"
    fi
}

# Compares a file's permission bits against the expected mode
# Usage: t_mode <label> <expected-mode> <path>
t_mode()
{
    if [ ! -e "$3" ]; then
        t_no "$1" "missing path: $3"
        return
    fi

    t_actual_mode=$($BUSYBOX stat -c '%a' "$3" 2>/dev/null)
    if [ "$t_actual_mode" = "$2" ]; then
        t_ok "$1"
    else
        t_no "$1" "expected mode: $2" "actual mode:   $t_actual_mode" "path: $3"
    fi
}

# Prints the totals, returning non-zero when a check failed
t_summary()
{
    echo
    if [ "$TEST_FAIL" -eq 0 ]; then
        echo "${TEST_BOLD}${TEST_PASS} passed, ${TEST_FAIL} failed, ${TEST_SKIP} skipped${TEST_RESET}"
        return 0
    fi

    echo "${TEST_RED}${TEST_PASS} passed, ${TEST_FAIL} failed, ${TEST_SKIP} skipped${TEST_RESET}"
    return 1
}
