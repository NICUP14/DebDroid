#!/system/bin/sh
# debdroid_probe.sh: Reports DebDroid's state as key=value lines, for programs
#
# "debdroid.sh status" is written for people, and its wording is governed by
# docs/reference/output.md, which means it is free to change. A program that
# scraped it would break the first time a sentence improved. This prints the
# same facts in a form that is a contract instead. The keys and their values
# are documented in docs/reference/probe.md.
#
# The contract: one "key=value" per line, keys are never renamed or given a
# new meaning, new keys may be added at any time, and a value never contains a
# newline. A fact that cannot be determined is printed with an empty value
# rather than left out, so a reader can tell "unknown" from "older probe".
#
# Usage: sh debdroid_probe.sh

# shellcheck disable=SC2154,SC2317,SC2329
# state_* are set by environment_state in debdroid_lib.sh, and the check
# functions below are called through report_test, which shellcheck cannot see.
#
# NOTE: Runs from two places. Installed, as "debdroid.sh probe". And from a
#       copy that is not installed at all, such as the payload the app carries,
#       because deciding what to install means asking a device that may have
#       nothing on it yet. So it requires nothing to be installed, never stops
#       early, and always exits 0: a refusal would tell the caller less than
#       the lines it would have printed.
# NOTE: Read-only. It never mounts, starts, creates or repairs anything, so it
#       is safe to run at any time, as often as a screen wants to refresh.

# Locates the helpers beside this script, the way the manager does
case "$0" in
    */*) DEBDROIDPROBE_DIR="${0%/*}" ;;
    *)   DEBDROIDPROBE_DIR=. ;;
esac

for file in debdroid_env.sh debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f "$DEBDROIDPROBE_DIR/$file" ]; then
        echo "Error: Missing required file: $DEBDROIDPROBE_DIR/$file" >&2
        exit 1
    fi
done

# Prefers the installed paths, as the user may have changed them, and falls
# back to the defaults carried beside this script
# NOTE: debdroid_env.sh is the file the installer preserves for exactly that
#       reason, so the installed copy is the one that says where things are.
if [ -f /sdcard/debdroid/debdroid_env.sh ]; then
    # shellcheck disable=SC1091
    . /sdcard/debdroid/debdroid_env.sh
else
    # shellcheck disable=SC1090
    . "$DEBDROIDPROBE_DIR"/debdroid_env.sh
fi
# shellcheck disable=SC1090
. "$DEBDROIDPROBE_DIR"/debdroid_ui.sh
# shellcheck disable=SC1090
. "$DEBDROIDPROBE_DIR"/debdroid_lib.sh

# Uses the bundled busybox when nothing is installed yet
# NOTE: The execute bit is restored first, for the same reason install.sh
#       restores it: a copy extracted from a zip arrives at 0644.
DEBDROIDPROBE_BUNDLED="$DEBDROIDPROBE_DIR"/../bin/busybox
if [ ! -x "$BUSYBOX" ] && [ -f "$DEBDROIDPROBE_BUNDLED" ]; then
    [ -x "$DEBDROIDPROBE_BUNDLED" ] || chmod 755 "$DEBDROIDPROBE_BUNDLED" 2> /dev/null
    [ -x "$DEBDROIDPROBE_BUNDLED" ] && BUSYBOX=$DEBDROIDPROBE_BUNDLED
fi

# Falls back to a busybox the system provides, staying quiet when there is none
find_busybox

# Prints one line of the report
# NOTE: Newlines are flattened, so a value can never forge a second key.
report_key()
{
    case "$2" in
        *'
'*) echo "$1=$(echo "$2" | $BUSYBOX tr '\n' ' ')" ;;
        *) echo "$1=$2" ;;
    esac
}

# Reports yes or no for whether a command succeeds
report_test()
{
    probe_key=$1
    shift
    if "$@"; then
        report_key "$probe_key" yes
    else
        report_key "$probe_key" no
    fi
}

# Checks for super-user permissions, with the toolbox "id" when busybox is absent
is_root()
{
    if [ -x "$BUSYBOX" ]; then
        [ "$($BUSYBOX id -u 2> /dev/null)" = "0" ]
    else
        [ "$(id -u 2> /dev/null)" = "0" ]
    fi
}

# Reads the version recorded in an installed copy of the scripts, without
# sourcing it
installed_version()
{
    [ -f "$DEBDROID_SDHOME"/debdroid_lib.sh ] || return 0
    $BUSYBOX sed -n "s/^export DEBDROID_VER='\(.*\)'$/\1/p" \
        "$DEBDROID_SDHOME"/debdroid_lib.sh 2> /dev/null | $BUSYBOX head -n 1
}

# Checks that every binary the scripts cannot run without is installed
binaries_present()
{
    for bin in busybox e2fsck resize2fs; do
        [ -f "$DEBDROID_BIN/$bin" ] || return 1
    done
}

# Checks that those binaries are also executable, which a flash at 0644 breaks
binaries_ok()
{
    for bin in busybox e2fsck resize2fs; do
        [ -x "$DEBDROID_BIN/$bin" ] || return 1
    done
}

# Checks for the ext2 superblock magic, 0xEF53 stored little-endian at byte 1080
# NOTE: This is the cheap answer to "is this image usable". Hashing a gigabyte
#       takes minutes on these devices, so the full checksum belongs to the
#       download, where it is the trust anchor, and not to every probe.
image_ok()
{
    [ -f "$DEBDROID_IMG" ] || return 1
    probe_magic=$($BUSYBOX dd if="$DEBDROID_IMG" bs=1 skip=1080 count=2 2> /dev/null |
        $BUSYBOX od -An -tx1 2> /dev/null | $BUSYBOX tr -d ' \n')
    [ "$probe_magic" = "53ef" ]
}

# Checks for a download the image script left unfinished
image_partial()
{
    [ -e "$DEBDROID_IMG".download ] || [ -e "$DEBDROID_IMG".download.gz ]
}

# Checks for the DebDroid module, ignoring one already marked for removal
module_installed()
{
    for dir in /data/adb/modules/debdroid /data/adb/modules_update/debdroid; do
        [ -d "$dir" ] || continue
        [ -e "$dir"/remove ] && continue
        return 0
    done
    return 1
}

# Names the root manager, which decides how starting at boot can be offered
root_manager()
{
    if [ -d /data/adb/ksu ]; then
        echo kernelsu
    elif [ -d /data/adb/ap ]; then
        echo apatch
    elif [ -d /data/adb/magisk ] || [ -e /sbin/magisk ]; then
        echo magisk
    elif [ -d /data/adb ]; then
        echo unknown
    else
        echo none
    fi
}

# Names the servers running in the environment, among the ones DebDroid ships
# NOTE: Read from each process's name in the supervisor's mount namespace, the
#       same test environment_state counts processes with, so it sees exactly
#       the processes "stop" would end.
running_services()
{
    [ -n "$state_pid" ] || return 0
    services_found=
    for proc in /proc/[0-9]*; do
        [ "$proc"/ns/mnt -ef /proc/"$state_pid"/ns/mnt ] || continue
        read -r services_name < "$proc"/comm 2> /dev/null || continue
        case "$services_name" in
            sshd) service=ssh ;;
            Xvnc|Xtigervnc|Xtightvnc|vncserver) service=vnc ;;
            *) continue ;;
        esac
        case ",$services_found," in
            *",$service,"*) ;;
            *) services_found="${services_found:+$services_found,}$service" ;;
        esac
    done
    echo "$services_found"
}

# Reads when the background environment started, from its pid file's age
started_at()
{
    [ -n "$state_pid" ] || return 0
    $BUSYBOX stat -c %Y "$DEBDROID_PID" 2> /dev/null
}

# Names what starts the environment at boot, if anything does
# NOTE: A module flashed while the device is running is staged in
#       modules_update and moves into place at the next reboot, so its hook is
#       there too. Missing it made a freshly flashed module look as if nothing
#       started DebDroid at boot, and the app offered to add a second hook.
boot_hook()
{
    if module_installed && { [ -f /data/adb/modules/debdroid/service.sh ] ||
            [ -f /data/adb/modules_update/debdroid/service.sh ]; }; then
        echo module
    elif [ -f /data/adb/service.d/debdroid.sh ]; then
        echo service_d
    else
        echo none
    fi
}

report_key probe 1
report_key version "$DEBDROID_VER"
report_key installed_version "$(installed_version)"

if [ -x "$BUSYBOX" ]; then
    report_key busybox ok
else
    # Everything below needs busybox, so this is as far as an honest report goes
    report_key busybox missing
    report_test root is_root
    exit 0
fi

report_test root is_root

check_arch
report_key abi "$DEBDROID_ABI"
report_key arch "$DEBDROID_ARCH"
report_test arch_built arch_is_built
report_key sdk "$(get_prop ro.build.version.sdk)"

report_test scripts_present [ -f "$DEBDROID_SDHOME"/debdroid.sh ]
report_test binaries_present binaries_present
report_test binaries_ok binaries_ok

report_test image_present [ -f "$DEBDROID_IMG" ]
if [ -f "$DEBDROID_IMG" ]; then
    report_key image_size "$($BUSYBOX stat -c %s "$DEBDROID_IMG" 2> /dev/null)"
else
    report_key image_size ""
fi
report_test image_ok image_ok
report_test image_partial image_partial

environment_state "$DEBDROID_ENV"
report_test running [ "$state_session" = "background" ]
report_key session "$state_session"
report_key pid "$state_pid"
report_key mounts "$state_mounts"
report_key processes "$state_processes"
report_key wakelock "$state_wakelock"
report_key swap "$state_swap"
report_key started "$(started_at)"
report_key services "$(running_services)"
report_test mount_ns [ -e /proc/self/ns/mnt ]
report_test swapfile [ -f "$DEBDROID_SWAP" ]

report_test module_installed module_installed
report_key root_manager "$(root_manager)"
report_key boot_hook "$(boot_hook)"
report_key log "$DEBDROID_LOG"

exit 0
