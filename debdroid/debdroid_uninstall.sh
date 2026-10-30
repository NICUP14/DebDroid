#!/system/bin/sh
# debdroid_uninstall.sh: Removes DebDroid from the device
#
# The debian image holds the user's data, so it is kept unless deletion is
# asked for explicitly. Everything this script removes is reported by path.
#
# Usage: debdroid.sh uninstall [--image] [--all] [--yes]
#
#   --image  Also delete the debian image and everything in it
#   --all    Also delete the image and the configuration files
#   --yes    Skip the confirmation prompt

for file in debdroid_env.sh debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: /sdcard/debdroid/$file" >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_ui.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_lib.sh
require_busybox
setup_colors
require_root

REMOVE_IMAGE=no
REMOVE_CONFIG=no
ASSUME_YES=no

for arg in "$@"; do
    case "$arg" in
        --image) REMOVE_IMAGE=yes ;;
        --all)   REMOVE_IMAGE=yes; REMOVE_CONFIG=yes ;;
        --yes)   ASSUME_YES=yes ;;
        *)
            error "Unknown option: \"$arg\"" \
                "Usage: $0 [--image] [--all] [--yes]"
            exit 1
            ;;
    esac
done

# Refuses to delete a path that is empty, relative or dangerously short
# NOTE: This script runs as root with paths from a sourced configuration file.
#       An unset variable would turn "rm -rf $VAR/" into "rm -rf /", so every
#       path is checked before it is handed to rm.
safe_remove()
{
    case "$1" in
        '' | /)
            error "Refusing to remove an unsafe path: \"$1\""
            return 1
            ;;
        /*/*/*) ;;
        *)
            error "Refusing to remove a suspiciously short path: \"$1\""
            return 1
            ;;
    esac

    [ -e "$1" ] || return 0

    if ! $BUSYBOX rm -rf "$1"; then
        error "Failed to remove \"$1\""
        return 1
    fi

    step "Removed $1"
}

section "Uninstalling DebDroid v$DEBDROID_VER"

# A running session holds the image, and removing files under it would corrupt it
if read_supervisor 2> /dev/null; then
    error "A background environment is running." \
        "Stop it first: \"debdroid.sh stop\""
    exit 1
fi
if ! ( $BUSYBOX flock -n 9 ) 9> "$DEBDROID_LOCK" 2> /dev/null; then
    error "A session is using the debian image." \
        "Exit it before uninstalling."
    exit 1
fi

# NOTE: Deleting the mount point while the image is still mounted there would
#       delete the contents of the user's debian installation instead of an
#       empty directory. Refuse rather than try to be clever about it.
if $BUSYBOX mountpoint -q "$DEBDROID_ENV" 2> /dev/null; then
    error "The debian image is still mounted at \"$DEBDROID_ENV\"" \
        "Reboot the device and run the uninstaller again."
    exit 1
fi

# Reports what is about to happen before anything is touched
step "Will remove: $DEBDROID_HOME (binaries, libraries, mount point)"
step "Will remove: $DEBDROID_SDHOME scripts, commands, tests and boot hook"
if [ "$REMOVE_IMAGE" = "yes" ]; then
    step "Will DELETE the debian image: $DEBDROID_IMG"
else
    step "Will keep the debian image: $DEBDROID_IMG"
fi
if [ "$REMOVE_CONFIG" = "yes" ]; then
    step "Will delete your configuration: environment, debdroid_env.sh"
else
    step "Will keep your configuration: environment, debdroid_env.sh"
fi

if [ -f /data/adb/service.d/debdroid.sh ]; then
    step "Will remove the boot hook: /data/adb/service.d/debdroid.sh"
fi
if [ -f "$DEBDROID_SWAP" ]; then
    step "Will remove the swapfile: $DEBDROID_SWAP"
fi

if [ "$ASSUME_YES" != "yes" ]; then
    echo
    printf 'Type "yes" to continue: '
    read -r answer
    if [ "$answer" != "yes" ]; then
        step "Cancelled, nothing was removed"
        exit 0
    fi
fi

echo
section "Removing DebDroid"

# Releases the swapfile before deleting it, or the kernel keeps using it
if [ -f "$DEBDROID_SWAP" ]; then
    if $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2> /dev/null; then
        step "Disabling swap"
        if ! $BUSYBOX swapoff "$DEBDROID_SWAP" > /dev/null 2>&1; then
            warn "Failed to disable the swapfile, keeping it." \
                "Reboot and delete \"$DEBDROID_SWAP\" by hand."
        fi
    fi
    $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2> /dev/null ||
        safe_remove "$DEBDROID_SWAP"
fi

# Drops the wakelock, so the device is not left awake forever
if [ -w /sys/power/wake_unlock ]; then
    echo "$DEBDROID_WAKELOCK" > /sys/power/wake_unlock 2> /dev/null
fi

if [ -f /data/adb/service.d/debdroid.sh ]; then
    $BUSYBOX rm -f /data/adb/service.d/debdroid.sh
    step "Removed the boot hook"
fi

if [ -d /data/adb/modules/debdroid ]; then
    step "Marking the magisk module for removal on the next reboot"
    : > /data/adb/modules/debdroid/remove
fi

safe_remove "$DEBDROID_HOME" || exit 1

# Removes the installed scripts, keeping whatever the user chose to keep
for name in debdroid.sh debdroid_mgr.sh debdroid_resize.sh debdroid_ui.sh \
    debdroid_lib.sh debdroid_uninstall.sh debdroid_update.sh; do
    [ -e "$DEBDROID_SDHOME/$name" ] && $BUSYBOX rm -f "$DEBDROID_SDHOME/$name"
done
step "Removed the scripts in $DEBDROID_SDHOME"

safe_remove "$DEBDROID_SDHOME"/command
safe_remove "$DEBDROID_SDHOME"/tests
safe_remove "$DEBDROID_SDHOME"/boot

if [ "$REMOVE_CONFIG" = "yes" ]; then
    $BUSYBOX rm -f "$DEBDROID_SDHOME"/environment "$DEBDROID_SDHOME"/debdroid_env.sh \
        "$DEBDROID_SDHOME"/environment.new "$DEBDROID_SDHOME"/debdroid_env.sh.new
    step "Removed your configuration files"
fi

if [ "$REMOVE_IMAGE" = "yes" ]; then
    safe_remove "$DEBDROID_IMG"
    safe_remove "$DEBDROID_SDHOME"/img
fi

echo
section "DebDroid has been uninstalled"

if [ "$REMOVE_IMAGE" != "yes" ] && [ -f "$DEBDROID_IMG" ]; then
    step "Your debian image was kept: $DEBDROID_IMG"
    step "Delete it by hand, or re-run with --image, to reclaim the space"
fi
if [ "$REMOVE_CONFIG" != "yes" ] && [ -f "$DEBDROID_SDHOME"/environment ]; then
    step "Your configuration was kept in $DEBDROID_SDHOME"
fi
if [ -d /data/adb/modules/debdroid ]; then
    step "Reboot to finish removing the magisk module"
fi
