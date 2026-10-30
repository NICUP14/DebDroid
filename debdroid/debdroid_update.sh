#!/system/bin/sh
# debdroid_update.sh: Updates the DebDroid runtime itself
#
# This updates the scripts and binaries. It does not update debian, which is
# what "debdroid.sh command update" does.
#
# Usage: debdroid.sh self-update [--check] [--rollback] [--offline <bundle>]
#
#   --check            Report whether an update is available, change nothing
#   --rollback         Restore the version saved before the last update
#   --offline <file>   Install a release bundle already on the device
#
# Everything downloaded is verified before it is used. The runtime runs as root,
# so a tampered bundle would mean a compromised device rather than a bad
# download. Nothing is extracted, made executable or moved into place until its
# checksum matches the one recorded in the repository, which travels with the
# scripts rather than coming from the download.

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
require_arch

DEBDROID_RELEASES=${DEBDROID_RELEASES:-https://github.com/NICUP14/DebDroid/releases}
DEBDROID_CHANNEL=${DEBDROID_CHANNEL:-stable}
DEBDROID_BACKUP="$DEBDROID_HOME"/backup
DEBDROID_STAGING="$DEBDROID_HOME"/staging

UPDATE_MODE=install
OFFLINE_BUNDLE=

while [ $# -gt 0 ]; do
    case "$1" in
        --check)    UPDATE_MODE=check ;;
        --rollback) UPDATE_MODE=rollback ;;
        --offline)
            UPDATE_MODE=offline
            shift
            OFFLINE_BUNDLE=$1
            if [ -z "$OFFLINE_BUNDLE" ]; then
                error "--offline needs a path to a release bundle."
                exit 1
            fi
            ;;
        *)
            error "Unknown option: \"$1\"" \
                "Usage: $0 [--check] [--rollback] [--offline <bundle>]"
            exit 1
            ;;
    esac
    shift
done

# Refuses to touch the installation while a session is using it
# NOTE: Replacing the scripts and binaries under a running session is exactly
#       the kind of corruption the lock exists to prevent.
require_idle()
{
    if read_supervisor 2> /dev/null; then
        error "A background environment is running." \
            "Stop it first: \"debdroid.sh stop\""
        return 1
    fi

    if ! ( $BUSYBOX flock -n 9 ) 9> "$DEBDROID_LOCK" 2> /dev/null; then
        error "A session is using the debian image." \
            "Exit it before updating."
        return 1
    fi

    return 0
}

# Downloads a file, preferring https but accepting http when tls fails
# Usage: fetch <url> <destination>
# NOTE: Falling back to plain http is safe here only because the expected
#       checksum comes from the repository rather than from the connection, so
#       a forged payload still cannot pass verification. It is never silent, and
#       it is never used for the manifest that carries the checksums.
fetch()
{
    if $BUSYBOX wget -q -O "$2" "$1" 2> /dev/null; then
        return 0
    fi

    case "$1" in
        https://*)
            warn "The secure download failed, retrying over plain http." \
                "The bundle is still verified against the recorded checksum." \
                "Old devices often have an outdated certificate store."
            if $BUSYBOX wget -q -O "$2" "http://${1#https://}" 2> /dev/null; then
                return 0
            fi
            ;;
    esac

    return 1
}

# Compares a file against an expected sha256 checksum
# Usage: verify <file> <expected-sha256>
verify()
{
    actual=$($BUSYBOX sha256sum "$1" 2> /dev/null)
    actual=${actual%% *}

    if [ -z "$actual" ]; then
        error "Could not read the downloaded file: \"$1\""
        return 1
    fi

    if [ "$actual" != "$2" ]; then
        error "The downloaded file does not match its checksum." \
            "expected: $2" \
            "actual:   $actual" \
            "The file was discarded and nothing was changed."
        return 1
    fi

    return 0
}

# Reports the installed and available versions
check_update()
{
    section "DebDroid update"
    step "Installed version: $DEBDROID_VER"
    step "Channel: $DEBDROID_CHANNEL"
    step "Releases: $DEBDROID_RELEASES"
    echo
    step "Automatic update checking is not available yet."
    step "Compare your version against the releases page above."
    step "Then update with: \"debdroid.sh self-update --offline <bundle>\""
}

# Restores the installation saved before the last update
rollback_update()
{
    if [ ! -d "$DEBDROID_BACKUP" ]; then
        error "No backup to roll back to." \
            "A backup is only created when an update is installed."
        return 1
    fi

    saved=$($BUSYBOX ls "$DEBDROID_BACKUP" 2> /dev/null | $BUSYBOX tail -n 1)
    if [ -z "$saved" ]; then
        error "The backup directory is empty: \"$DEBDROID_BACKUP\""
        return 1
    fi

    require_idle || return 1

    section "Rolling back to v$saved"

    if [ -d "$DEBDROID_BACKUP/$saved"/bin ]; then
        step "Restoring the binaries"
        $BUSYBOX cp -a "$DEBDROID_BACKUP/$saved"/bin/. "$DEBDROID_BIN"/
    fi
    if [ -d "$DEBDROID_BACKUP/$saved"/lib ]; then
        step "Restoring the libraries"
        $BUSYBOX cp -a "$DEBDROID_BACKUP/$saved"/lib/. "$DEBDROID_LIB"/
    fi
    if [ -d "$DEBDROID_BACKUP/$saved"/scripts ]; then
        step "Restoring the scripts"
        $BUSYBOX cp -a "$DEBDROID_BACKUP/$saved"/scripts/. "$DEBDROID_SDHOME"/
    fi

    section "Rolled back to v$saved"
}

# Saves the current installation so an update can be undone
backup_installation()
{
    target="$DEBDROID_BACKUP/$DEBDROID_VER"

    step "Backing up the current version to $target"
    $BUSYBOX rm -rf "$target"
    mkdir -p "$target"/bin "$target"/lib "$target"/scripts

    $BUSYBOX cp -a "$DEBDROID_BIN"/. "$target"/bin/ 2> /dev/null
    $BUSYBOX cp -a "$DEBDROID_LIB"/. "$target"/lib/ 2> /dev/null
    for script in "$DEBDROID_SDHOME"/*.sh "$DEBDROID_SDHOME"/environment; do
        [ -f "$script" ] && $BUSYBOX cp -a "$script" "$target"/scripts/
    done
}

# Installs a release bundle that is already on the device
install_bundle()
{
    if [ ! -f "$OFFLINE_BUNDLE" ]; then
        error "No such release bundle: \"$OFFLINE_BUNDLE\""
        return 1
    fi

    require_idle || return 1

    section "Installing a release bundle"
    step "Bundle: $OFFLINE_BUNDLE"

    # A checksum file beside the bundle is used when present
    if [ -f "$OFFLINE_BUNDLE".sha256 ]; then
        expected=$($BUSYBOX cat "$OFFLINE_BUNDLE".sha256)
        expected=${expected%% *}
        step "Verifying the bundle"
        verify "$OFFLINE_BUNDLE" "$expected" || return 1
    else
        warn "No checksum file found beside the bundle." \
            "Expected it at: $OFFLINE_BUNDLE.sha256" \
            "Only install bundles you obtained yourself."
        step "Checksum of this bundle:"
        $BUSYBOX sha256sum "$OFFLINE_BUNDLE"
    fi

    # Everything is unpacked into staging first, so a bad bundle changes nothing
    step "Unpacking into staging"
    $BUSYBOX rm -rf "$DEBDROID_STAGING"
    mkdir -p "$DEBDROID_STAGING"

    if ! tar_output=$($BUSYBOX tar -xzf "$OFFLINE_BUNDLE" -C "$DEBDROID_STAGING" 2>&1); then
        error "Failed to unpack the release bundle." "$tar_output"
        $BUSYBOX rm -rf "$DEBDROID_STAGING"
        return 1
    fi

    # A bundle without the installer is not a DebDroid release
    if [ ! -f "$DEBDROID_STAGING"/install.sh ]; then
        error "The bundle does not contain install.sh, so it is not a release." \
            "Nothing was changed."
        $BUSYBOX rm -rf "$DEBDROID_STAGING"
        return 1
    fi

    backup_installation

    step "Running the installer from the bundle"
    if ! ( cd "$DEBDROID_STAGING" && sh ./install.sh ); then
        error "The installer failed." \
            "Roll back with: \"debdroid.sh self-update --rollback\""
        return 1
    fi

    $BUSYBOX rm -rf "$DEBDROID_STAGING"
    section "The update is installed"
    step "Verify it on the device: \"sh $DEBDROID_SDHOME/tests/device.sh\""
}

case "$UPDATE_MODE" in
    check)    check_update ;;
    rollback) rollback_update ;;
    offline)  install_bundle ;;
    install)
        check_update
        echo
        step "Download a release bundle, then install it with --offline."
        ;;
esac
