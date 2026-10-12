#!/system/bin/sh
# debdroid_ui.sh: Output helpers shared by the DebDroid scripts
#
# Errors and warnings go to stderr, everything else to stdout.
# Colors require the busybox binary, as older Android shells lack printf.

# Enables colored output when printing to a terminal (disabled by NO_COLOR)
setup_colors()
{
    if [ -t 1 ] && [ -t 2 ] && [ -z "$NO_COLOR" ] && [ -x "$BUSYBOX" ]; then
        COLOR_BOLD=$($BUSYBOX printf '\033[1m')
        COLOR_RED=$($BUSYBOX printf '\033[1;31m')
        COLOR_PURPLE=$($BUSYBOX printf '\033[1;35m')
        COLOR_RESET=$($BUSYBOX printf '\033[0m')
    fi
}

# Prints a section header (i.e. starting or stopping the environment)
section()
{
    echo "${COLOR_BOLD}:: $1${COLOR_RESET}"
}

# Prints a step of the current section
step()
{
    echo " -> $1"
}

# Prints a colored label and message to stderr, followed by indented hint lines
# Usage: report <color> <label> <message> [hint...]
report()
{
    echo "$1$2:${COLOR_RESET} $3" >&2
    shift 3
    for hint in "$@"; do
        [ -n "$hint" ] || continue
        echo "$hint" | while IFS= read -r line; do
            echo "    $line"
        done >&2
    done
}

# Reports a failure the script can continue without
warn()
{
    report "$COLOR_PURPLE" Warning "$@"
}

# Reports a failure the script cannot continue without (the caller exits)
error()
{
    report "$COLOR_RED" Error "$@"
}
