#!/system/bin/sh
# debdroid_lib.sh: Shared helpers used by the DebDroid scripts
#
# Sourced by every entry point, after debdroid_ui.sh, as the "require_" helpers
# report through it. Only POSIX shell features and the bundled busybox are used,
# as the android toolbox of older devices lacks most utilities.

# Locates the busybox binary, checking the popular install locations
# Sets "BUSYBOX" unless it already points to an executable
# NOTE: An unusable value is replaced, as debdroid_env.sh sets BUSYBOX to the
#       install path, which does not exist yet during a fresh install.
find_busybox()
{
    [ -x "$BUSYBOX" ] && return 0

    for candidate in \
        /data/local/debdroid/bin/busybox \
        /sbin/busybox \
        /system/bin/busybox \
        /system/xbin/busybox \
        /data/local/bin/busybox
    do
        if [ -x "$candidate" ]; then
            BUSYBOX=$candidate
            return 0
        fi
    done

    return 1
}

# Locates the busybox binary, stopping the script when it is missing
require_busybox()
{
    if ! find_busybox; then
        error "Cannot locate the busybox binary." \
            "Manually set it using the \$BUSYBOX variable."
        exit 1
    fi
}

# Checks if the script runs with super-user permissions
# NOTE: Uses busybox, as the toolbox "id" of very old devices lacks "-u".
require_root()
{
    if [ "$($BUSYBOX id -u)" -ne 0 ]; then
        error "Missing required super-user permissions."
        exit 1
    fi
}

# Maps the device's android abi to the matching debian architecture
# Sets "DEBDROID_ARCH" to the debian architecture name, for the caller to read
# shellcheck disable=SC2034
check_arch()
{
    DEBDROID_ABI=$($BUSYBOX getprop ro.product.cpu.abi)

    # Falls back to the abi list, as very old devices lack the single-abi property
    if [ -z "$DEBDROID_ABI" ]; then
        DEBDROID_ABI=$($BUSYBOX getprop ro.product.cpu.abilist)
        DEBDROID_ABI=${DEBDROID_ABI%%,*}
    fi

    case "$DEBDROID_ABI" in
        arm64-v8a) DEBDROID_ARCH=arm64 ;;
        *)         DEBDROID_ARCH= ; return 1 ;;
    esac
}

# Checks for a supported architecture, stopping the script otherwise
require_arch()
{
    if ! check_arch; then
        error "Unsupported architecture: \"$DEBDROID_ABI\"" \
            "This script only works on arm64-v8a (aarch64)."
        exit 1
    fi
}

# Checks that the given size is an integer followed by K, M or G
# NOTE: Both e2fsprogs tools read K/M/G as powers of 1024, while a bare number
#       means blocks to resize2fs, so the unit is required rather than optional.
check_size()
{
    # Splits the unit off the end of the size
    case "$1" in
        *[KMG]) size_number=${1%?} ;;
        *)      return 1 ;;
    esac

    # Rejects a missing or non-numeric amount
    case "$size_number" in
        ''|*[!0-9]*) return 1 ;;
    esac
}

# Quotes the arguments into a single string that survives one shell evaluation
# Sets "DEBDROID_QUOTED" to the quoted string, empty when given no arguments
# NOTE: Single quotes protect every character except a single quote itself, which
#       is closed, backslash-escaped and reopened ('\'') to pass through intact.
#       Needed because the chroot command is handed to "su -c" as one string.
shell_quote()
{
    DEBDROID_QUOTED=

    for arg in "$@"; do
        quoted=

        # Escapes every single quote, copying the text in between verbatim
        while :; do
            case "$arg" in
                *\'*)
                    quoted="$quoted${arg%%\'*}'\\''"
                    arg=${arg#*\'}
                    ;;
                *)
                    quoted="$quoted$arg"
                    break
                    ;;
            esac
        done

        DEBDROID_QUOTED="${DEBDROID_QUOTED:+$DEBDROID_QUOTED }'$quoted'"
    done
}
