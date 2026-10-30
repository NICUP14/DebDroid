#!/system/bin/sh
# debdroid_swap.sh: Creates and manages the swapfile DebDroid sessions enable
#
# Swap keeps memory-hungry desktops from triggering an out-of-memory reboot on
# devices with little RAM. Creating the file is slow, so it happens here once
# rather than during every session start.
#
# Usage: debdroid.sh swap <create [size] | remove | status>
#
# NOTE: This runs on the android side, not inside debian: swap belongs to the
#       device. It therefore lives here rather than in command/, whose scripts
#       the frontend runs inside the chroot - where /data is not mounted and
#       busybox is at a different path, so this would fail confusingly.

# Refuses to run inside the chroot, where none of this would work
# NOTE: /debdroid/bin only exists inside a session, as that is where the
#       bundled binaries are mounted.
if [ -d /debdroid/bin ]; then
    echo "Error: This manages the device's swap and must run outside debian." >&2
    echo "    Exit the session, then run: debdroid.sh swap $*" >&2
    exit 1
fi

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

SWAP_OPT=${1:-status}
SWAP_SIZE=${2:-1G}

# Reports whether the swapfile is currently in use by the kernel
swap_active()
{
    [ -f /proc/swaps ] || return 1
    $BUSYBOX grep -q "^$DEBDROID_SWAP " /proc/swaps 2> /dev/null
}

# Checks that the kernel and the filesystem can host a swapfile at all
check_swap_support()
{
    if [ ! -f /proc/swaps ]; then
        error "This kernel does not support swap." \
            "It was built without CONFIG_SWAP, so there is nothing to enable."
        return 1
    fi

    # NOTE: f2fs rejects swapon on a regular file on the kernels DebDroid
    #       targets, so this fails clearly instead of leaving a cryptic error.
    data_fs=$($BUSYBOX awk '$2 == "/data" { print $3; exit }' /proc/self/mounts)
    if [ "$data_fs" = "f2fs" ]; then
        error "A swapfile cannot live on an f2fs filesystem." \
            "\"/data\" is f2fs on this device, where swapon on a file fails." \
            "Use a zram-based swap module instead."
        return 1
    fi

    return 0
}

# Creates the swapfile and prepares it for use
create_swap()
{
    if ! check_size "$SWAP_SIZE"; then
        error "Invalid size: \"$SWAP_SIZE\"" \
            "Expected an integer followed by K, M or G (e.g. 2G)."
        return 1
    fi

    check_swap_support || return 1

    if swap_active; then
        error "The swapfile is in use." \
            "Run \"swap remove\" before creating a new one."
        return 1
    fi

    # Converts the size into the mebibyte count dd writes
    case "$SWAP_SIZE" in
        *K) blocks=$(( ${SWAP_SIZE%K} / 1024 )) ;;
        *M) blocks=${SWAP_SIZE%M} ;;
        *G) blocks=$(( ${SWAP_SIZE%G} * 1024 )) ;;
    esac

    if [ "$blocks" -lt 1 ]; then
        error "The requested size is too small: \"$SWAP_SIZE\"" \
            "Use at least 1M."
        return 1
    fi

    # Refuses to fill the device, which would break android itself
    available=$($BUSYBOX df -k "$DEBDROID_HOME" 2> /dev/null |
        $BUSYBOX awk 'NR == 2 { print int($4 / 1024) }')
    if [ -n "$available" ] && [ "$available" -lt "$blocks" ]; then
        error "Not enough free space for a ${SWAP_SIZE} swapfile." \
            "Free space: ${available}M, required: ${blocks}M."
        return 1
    fi

    section "Creating a ${SWAP_SIZE} swapfile"
    step "Writing \"$DEBDROID_SWAP\" (this takes a few minutes)"

    # NOTE: The file is written out rather than preallocated: swapon refuses a
    #       file with holes or unwritten extents, which is what fallocate
    #       produces on ext4.
    $BUSYBOX rm -f "$DEBDROID_SWAP"
    if ! dd_output=$($BUSYBOX dd if=/dev/zero of="$DEBDROID_SWAP" \
            bs=1048576 count="$blocks" 2>&1); then
        error "Failed to write the swapfile." "$dd_output"
        $BUSYBOX rm -f "$DEBDROID_SWAP"
        return 1
    fi

    # A readable swapfile would leak whatever memory was swapped out
    chmod 600 "$DEBDROID_SWAP"

    step "Formatting the swapfile"
    if ! mkswap_output=$($BUSYBOX mkswap "$DEBDROID_SWAP" 2>&1); then
        error "Failed to format the swapfile." "$mkswap_output"
        $BUSYBOX rm -f "$DEBDROID_SWAP"
        return 1
    fi

    step "Created"
    step "Enable it by setting CONFIG_ENV_SWAP=yes in debdroid_mgr.sh"
}

# Disables and deletes the swapfile
remove_swap()
{
    if [ ! -f "$DEBDROID_SWAP" ]; then
        error "No swapfile exists: \"$DEBDROID_SWAP\""
        return 1
    fi

    section "Removing the swapfile"

    if swap_active; then
        step "Disabling swap (this can take a while)"
        if ! swapoff_output=$($BUSYBOX swapoff "$DEBDROID_SWAP" 2>&1); then
            error "Failed to disable the swapfile." "$swapoff_output" \
                "Reboot the device and try again."
            return 1
        fi
    fi

    step "Deleting \"$DEBDROID_SWAP\""
    $BUSYBOX rm -f "$DEBDROID_SWAP"
    step "Removed"
}

# Reports the swapfile and the kernel's swap configuration
report_swap()
{
    section "Swap status"

    if [ -f "$DEBDROID_SWAP" ]; then
        step "Swapfile: $DEBDROID_SWAP ($($BUSYBOX du -h "$DEBDROID_SWAP" |
            $BUSYBOX cut -f 1))"
    else
        step "Swapfile: none, create one with \"swap create 2G\""
    fi

    if swap_active; then
        step "State: enabled"
    else
        step "State: not enabled"
    fi

    step "Configured: CONFIG_ENV_SWAP in debdroid_mgr.sh"
    step "Swappiness: $($BUSYBOX cat /proc/sys/vm/swappiness 2> /dev/null)"

    if [ -f /proc/swaps ]; then
        step "Active swap areas:"
        $BUSYBOX tail -n +2 /proc/swaps | while IFS= read -r line; do
            echo "    $line"
        done
    else
        step "Active swap areas: the kernel does not support swap"
    fi
}

case "$SWAP_OPT" in
    create) create_swap ;;
    remove) remove_swap ;;
    status) report_swap ;;
    *)
        error "Unknown option: \"$SWAP_OPT\"" \
            "Usage: debdroid.sh swap <create [size] | remove | status>"
        exit 1
        ;;
esac
