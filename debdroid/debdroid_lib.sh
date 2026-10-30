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

    # NOTE: DEBDROID_ARCH is the debian architecture name, which is what the
    #       rootfs and binary bundles are published under. It differs from the
    #       android abi name in every case, so the mapping is explicit.
    case "$DEBDROID_ABI" in
        arm64-v8a)   DEBDROID_ARCH=arm64 ;;
        armeabi-v7a) DEBDROID_ARCH=armhf ;;
        armeabi)     DEBDROID_ARCH=armel ;;
        x86_64)      DEBDROID_ARCH=amd64 ;;
        x86)         DEBDROID_ARCH=i386 ;;
        *)           DEBDROID_ARCH= ; return 1 ;;
    esac
}

# Reports whether the installation actually ships binaries for an architecture
# NOTE: check_arch recognising an abi is not the same as the binaries existing.
#       Shipping arch-aware scripts before every arch is built would otherwise
#       fail later with a confusing "cannot execute binary file".
arch_is_built()
{
    [ -n "$DEBDROID_ARCH" ] || return 1

    case "$DEBDROID_ARCH" in
        arm64) return 0 ;;
        *)     return 1 ;;
    esac
}

# Checks for a supported architecture, stopping the script otherwise
require_arch()
{
    if ! check_arch; then
        error "Unsupported architecture: \"$DEBDROID_ABI\"" \
            "DebDroid supports arm64-v8a, armeabi-v7a, x86_64 and x86."
        exit 1
    fi

    if ! arch_is_built; then
        error "No binaries are built for this architecture yet: \"$DEBDROID_ABI\"" \
            "DebDroid recognises it as debian \"$DEBDROID_ARCH\", but the" \
            "bundled binaries are arm64 only for now." \
            "Build them with package/build_all.sh, or follow the issue tracker."
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

# Reads the background supervisor's pid file, checking that it is still valid
# Usage: read_supervisor [pid-file]   (defaults to DEBDROID_PID)
# Sets "supervisor_pid" to the supervisor's process id
# NOTE: The namespace id is stored alongside the pid, as the kernel reuses pids
#       and signaling the wrong process would be dangerous.
# NOTE: Defined here rather than in the manager because the uninstaller and the
#       updater both need it to refuse running over a live environment.
read_supervisor()
{
    supervisor_pid=
    supervisor_mnt=
    read_supervisor_file=${1:-$DEBDROID_PID}

    [ -n "$read_supervisor_file" ] || return 1
    [ -f "$read_supervisor_file" ] || return 1

    read -r supervisor_pid supervisor_mnt < "$read_supervisor_file" 2>/dev/null
    [ -n "$supervisor_pid" ] && [ -n "$supervisor_mnt" ] || return 1
    [ -d /proc/"$supervisor_pid" ] || return 1

    current_mnt=$($BUSYBOX readlink /proc/"$supervisor_pid"/ns/mnt 2>/dev/null |
        $BUSYBOX cut -d ':' -f 2)
    [ "$current_mnt" = "$supervisor_mnt" ]
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
