#!/system/bin/sh

# Checks for the required debdroid files
for file in debdroid_env.sh debdroid_ui.sh; do
    if [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: /sdcard/debdroid/$file" >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. /sdcard/debdroid/debdroid_ui.sh
setup_colors

# Prints HELP Usage
if [ $# -eq 0 ]; then
echo "DebDroid resizer (https://github.com/NICUP14/DebDroid)
Author: NICUP14
Version: $DEBDROID_VER

Description:
  A lightweight utility to resize ext2 Debian images for DebDroid environments.

Usage:
  $0 [SIZE]

Arguments:
  SIZE        
       New target size for the image.
       Shrinking fails if the existing data does not fit in the new size.

Size Format:
  - Integer followed by a unit: K, M, G (powers of 1024)
  Examples: 512M, 5G, 10G

Notes:
  - Shrinking the image is not recommended unless necessary.

Examples:
  $0 5G    # Extend image to 5 GiB"
exit
fi

# Checks for a matching architecture
ARCH=$(getprop ro.product.cpu.abi)
if [ "$ARCH" != "arm64-v8a" ]; then
    error "Unsupported architecture: \"$ARCH\"" \
        "This script only works on arm64-v8a (aarch64)."
    exit 1
fi

# Check if the user has root permissions 
if [ "$(id -u)" -ne 0 ]; then
    error "Missing required super-user permissions."
    exit 1
fi

# shellcheck disable=SC2124
DEBDROIDRSZ_SIZE="$@"

# Checks for a valid size
if ! echo "$DEBDROIDRSZ_SIZE" | $BUSYBOX grep -Eq '^[0-9]+[KMG]$'; then
    error "Invalid size: \"$DEBDROIDRSZ_SIZE\"" \
        "Expected an integer followed by K, M or G (e.g. 5G)."
    exit 1
fi

# Resizes the debian image, expects the lock file to be open as fd 9
resize_image()
{
    # Ensures the image is not mounted
    # NOTE: Mounts live in a private namespace, so the lock is the only reliable check
    if ! $BUSYBOX flock -n 9; then
        error "Refusing to resize the debian image while the environment is running." \
            "Exit all running instances before resizing the image."
        return 1
    fi

    section "Resizing the debian image to $DEBDROIDRSZ_SIZE"

    step "Checking the debian image"
    fsck_output=$("$DEBDROID_BIN"/e2fsck -fp "$DEBDROID_IMG" 2>&1)
    fsck_status=$?

    # Exit codes 1 and 2 mean that errors were found and corrected
    if [ "$fsck_status" -ge 4 ]; then
        error "Failed to repair the debian image." "$fsck_output" \
            "Repair it manually: \"$DEBDROID_BIN/e2fsck -f $DEBDROID_IMG\""
        return 1
    elif [ "$fsck_status" -ne 0 ]; then
        warn "Repaired errors in the debian image." "$fsck_output"
    fi

    # NOTE: resize2fs extends the image file when growing, truncate trims it when shrinking
    step "Resizing the filesystem"
    if ! resize_output=$("$DEBDROID_BIN"/resize2fs "$DEBDROID_IMG" "$DEBDROIDRSZ_SIZE" 2>&1); then
        error "Failed to resize the debian image." "$resize_output"
        return 1
    fi

    step "Resizing the image file"
    if ! truncate_output=$($BUSYBOX truncate -s "$DEBDROIDRSZ_SIZE" "$DEBDROID_IMG" 2>&1); then
        error "Failed to resize the image file." "$truncate_output"
        return 1
    fi

    step "Successfully resized the debian image"
}

# NOTE: Opens the lock with a per-command redirection, as POSIX leaves it unspecified
# whether fds opened by a bare "exec" reach other programs (i.e. flock)
resize_image 9> "$DEBDROID_LOCK"
