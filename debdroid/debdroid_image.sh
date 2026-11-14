#!/system/bin/sh
# debdroid_image.sh: Obtains the prebuilt debian image
#
# Called by the installer when the image is not already on the device. It asks
# the user nothing: it picks the right image, fetches it, verifies it against
# the checksum recorded in rootfs/manifest and unpacks it.
#
# The image is prebuilt and published as a release asset. Nobody installing
# DebDroid builds a filesystem, and nothing here should ever require them to.
#
# Usage: sh debdroid_image.sh <destination>

for file in debdroid_env.sh debdroid_ui.sh debdroid_lib.sh; do
    if [ ! -f ./debdroid/"$file" ] && [ ! -f /sdcard/debdroid/"$file" ]; then
        echo "Error: Missing required file: $file" >&2
        exit 1
    fi
done

# Runs from the repository during an install, and from the device afterwards
if [ -f ./debdroid/debdroid_env.sh ]; then
    DEBDROIDIMG_DIR=./debdroid
    DEBDROIDIMG_MANIFEST=./rootfs/manifest
else
    DEBDROIDIMG_DIR=/sdcard/debdroid
    DEBDROIDIMG_MANIFEST=/sdcard/debdroid/rootfs-manifest
fi

# shellcheck disable=SC1090
. "$DEBDROIDIMG_DIR"/debdroid_env.sh
# shellcheck disable=SC1090
. "$DEBDROIDIMG_DIR"/debdroid_ui.sh
# shellcheck disable=SC1090
. "$DEBDROIDIMG_DIR"/debdroid_lib.sh
require_busybox
require_arch

DEBDROIDIMG_DEST=$1
DEBDROIDIMG_RELEASES=${DEBDROID_RELEASES:-https://github.com/NICUP14/DebDroid/releases/latest/download}

if [ -z "$DEBDROIDIMG_DEST" ]; then
    error "Usage: $0 <destination>"
    exit 1
fi

# Reads the IMAGE record for this architecture out of the manifest
# Sets "image_sha" and "image_size"
read_manifest()
{
    image_sha=
    image_size=

    [ -f "$DEBDROIDIMG_MANIFEST" ] || return 1

    # Fields: id | kind | arch | release | sha256 | size | url | extras...
    # NOTE: The manifest is edited by hand, so every field is trimmed rather
    #       than matched against an exact amount of surrounding whitespace.
    manifest_line=$($BUSYBOX awk -F '|' -v arch="$DEBDROID_ARCH" '
        /^[ \t]*#/ { next }
        NF < 6 { next }
        {
            kind = $2; field_arch = $3
            gsub(/^[ \t]+|[ \t]+$/, "", kind)
            gsub(/^[ \t]+|[ \t]+$/, "", field_arch)
            if (kind == "IMAGE" && field_arch == arch) { print; exit }
        }
    ' "$DEBDROIDIMG_MANIFEST" 2>/dev/null)

    [ -n "$manifest_line" ] || return 1

    image_sha=$(echo "$manifest_line" |
        $BUSYBOX awk -F '|' '{ gsub(/[ \t]/, "", $5); print $5 }')
    image_size=$(echo "$manifest_line" |
        $BUSYBOX awk -F '|' '{ gsub(/[ \t]/, "", $6); print $6 }')

    [ -n "$image_sha" ] && [ "$image_sha" != "PLACEHOLDER" ]
}

# Follows the redirects of a url with the android toolbox curl, handing each
# target to busybox wget until one downloads
# Usage: fetch_redirected <url> <destination>
# NOTE: This exists because the two http clients on a device fail on opposite
#       hosts and each succeeds where the other does not. Measured on android
#       7.0 against the releases host:
#
#         busybox wget   github.com: TLS alert 80, reset   asset host: works
#         toolbox curl   github.com: works                 asset host: cert fails
#
#       busybox's built-in TLS is too old for the host that issues the
#       redirect, and the host the redirect points at serves a certificate from
#       a root younger than the device. So the redirect is resolved with curl,
#       which can talk to the first host, and the asset is pulled with busybox
#       wget, which can talk to the second. Neither step weakens anything:
#       busybox wget never validated certificates at all, so it is the same
#       client the primary path already uses, and the image is still only
#       accepted if it matches the checksum recorded in the manifest.
# NOTE: curl is asked for one hop at a time with no -L and the body thrown
#       away, so it never connects to the final host. That matters: connecting
#       there is exactly what it cannot do.
fetch_redirected()
{
    command -v curl > /dev/null 2>&1 || return 1

    fetch_url=$1
    fetch_hop=0

    while [ "$fetch_hop" -lt 5 ]; do
        fetch_next=$(curl -s -o /dev/null -w '%{redirect_url}' "$fetch_url" 2> /dev/null)
        [ -n "$fetch_next" ] || return 1

        $BUSYBOX wget -q -O "$2" "$fetch_next" 2> /dev/null && return 0

        fetch_url=$fetch_next
        fetch_hop=$((fetch_hop + 1))
    done

    return 1
}

# Downloads a url, preferring https and falling back loudly to plain http
# NOTE: The fallback is safe only because the expected checksum comes from the
#       manifest that shipped with these scripts, not from the connection. Old
#       devices often have a certificate store too old for a modern host.
fetch()
{
    $BUSYBOX wget -q -O "$2" "$1" 2> /dev/null && return 0

    # The redirect chain of a release asset crosses two hosts, and no single
    # client on the device can reach both
    fetch_redirected "$1" "$2" && return 0

    # A device whose certificate store is current, where only busybox is too old
    if command -v curl > /dev/null 2>&1 &&
            curl -fsSL --max-time 1800 -o "$2" "$1" 2> /dev/null; then
        return 0
    fi

    case "$1" in
        https://*)
            warn "The secure download failed, retrying over plain http." \
                "The image is still verified against its recorded checksum."
            $BUSYBOX wget -q -O "$2" "http://${1#https://}" 2> /dev/null && return 0
            ;;
    esac

    return 1
}

section "Obtaining the debian image"

if ! read_manifest; then
    error "No verified image is published for \"$DEBDROID_ARCH\" yet." \
        "Download the image from the releases page and place it at:" \
        "\"$DEBDROIDIMG_DEST\", then re-run the installer."
    exit 1
fi

step "Architecture: $DEBDROID_ARCH"

mkdir -p "${DEBDROIDIMG_DEST%/*}"
staging="$DEBDROIDIMG_DEST".download

# NOTE: Everything lands in staging and is verified before it is moved into
#       place, so a failed or tampered download leaves nothing behind that a
#       later step could mistake for a usable image.
step "Downloading (this is a few hundred megabytes)"
if ! fetch "$DEBDROIDIMG_RELEASES/debian.img.gz" "$staging".gz; then
    error "Failed to download the debian image." \
        "Check the device's network connection and try again."
    $BUSYBOX rm -f "$staging".gz
    exit 1
fi

step "Unpacking"
if ! $BUSYBOX gunzip -c "$staging".gz > "$staging"; then
    error "The downloaded image could not be unpacked." \
        "The download was probably incomplete; try again."
    $BUSYBOX rm -f "$staging".gz "$staging"
    exit 1
fi
$BUSYBOX rm -f "$staging".gz

# A wrong size is the common failure and costs nothing to spot, where hashing a
# gigabyte on a phone takes a while
if [ -n "$image_size" ] && [ "$image_size" != "0" ]; then
    actual_size=$($BUSYBOX wc -c < "$staging")
    actual_size=$(echo "$actual_size" | $BUSYBOX tr -d ' ')

    if [ "$actual_size" != "$image_size" ]; then
        error "The downloaded image is the wrong size." \
            "expected: $image_size bytes" \
            "actual:   $actual_size bytes" \
            "The download was incomplete. The file was discarded."
        $BUSYBOX rm -f "$staging"
        exit 1
    fi
fi

step "Verifying"
actual_sha=$($BUSYBOX sha256sum "$staging")
actual_sha=${actual_sha%% *}

if [ "$actual_sha" != "$image_sha" ]; then
    error "The downloaded image does not match its checksum." \
        "expected: $image_sha" \
        "actual:   $actual_sha" \
        "The file was discarded. Nothing was installed."
    $BUSYBOX rm -f "$staging"
    exit 1
fi

# Only now, with the checksum confirmed, does it become the real image
$BUSYBOX mv "$staging" "$DEBDROIDIMG_DEST"
step "Verified and installed"
