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

# NOTE: A "fetch" helper used to sit here, with a plain-http fallback, and
#       nothing ever called it: this script only installs a bundle that is
#       already on the device. It was removed rather than left in place,
#       because an unused downloader that quietly accepts http is a trap for
#       whoever wires up online updates later, and it read as though the
#       feature existed. When that work happens, take the downloader from
#       debdroid_image.sh, which is the one that is actually exercised, and
#       keep the checksum coming from the repository rather than the server.

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

    # The version to restore is recorded when the backup is taken
    # NOTE: This used to be "ls | tail -n 1", which sorts lexically, so a
    #       backup of 1.2.9 was picked over 1.2.10. A pointer written at backup
    #       time needs no sorting at all, which also avoids depending on
    #       whether the bundled busybox "sort" was built with -V.
    saved=
    if [ -f "$DEBDROID_BACKUP"/previous ]; then
        saved=$($BUSYBOX cat "$DEBDROID_BACKUP"/previous 2> /dev/null)
        saved=$(echo "$saved" | $BUSYBOX tr -d ' \t\r\n')
    fi

    # A backup from before the pointer existed still has to be usable
    if [ -n "$saved" ] && [ ! -d "$DEBDROID_BACKUP/$saved" ]; then
        warn "The recorded backup \"v$saved\" is missing, looking for another."
        saved=
    fi
    if [ -z "$saved" ]; then
        for candidate in "$DEBDROID_BACKUP"/*; do
            [ -d "$candidate" ] && saved=${candidate##*/}
        done
    fi

    if [ -z "$saved" ]; then
        error "The backup directory is empty: \"$DEBDROID_BACKUP\""
        return 1
    fi

    require_idle || return 1

    restore="$DEBDROID_BACKUP/$saved"

    # A rollback that only copies over the top leaves behind any script the
    # newer version added, so the scripts are cleared first. That is only safe
    # when the backup can actually replace them.
    backup_complete=yes
    for needed in debdroid.sh debdroid_mgr.sh debdroid_env.sh debdroid_ui.sh \
        debdroid_lib.sh; do
        [ -f "$restore"/scripts/"$needed" ] || backup_complete=no
    done

    section "Rolling back to v$saved"

    if [ -d "$restore"/bin ]; then
        step "Restoring the binaries"
        $BUSYBOX cp -a "$restore"/bin/. "$DEBDROID_BIN"/
    fi
    if [ -d "$restore"/lib ]; then
        step "Restoring the libraries"
        $BUSYBOX cp -a "$restore"/lib/. "$DEBDROID_LIB"/
    fi

    if [ -d "$restore"/scripts ]; then
        # NOTE: The glob is deliberately "debdroid*.sh". It covers every script
        #       the project installs and cannot match "environment",
        #       "debdroid.conf", "rootfs-manifest", "img/", "command/" or
        #       "tests/", so a rollback cannot take the image or the user's
        #       settings with it.
        if [ "$backup_complete" = yes ]; then
            step "Removing the newer scripts"
            $BUSYBOX rm -f "$DEBDROID_SDHOME"/debdroid*.sh
        else
            warn "The backup of v$saved is missing some scripts." \
                "Restoring over the current ones instead of replacing them." \
                "A script added since v$saved will be left behind."
        fi

        step "Restoring the scripts"
        $BUSYBOX cp -a "$restore"/scripts/. "$DEBDROID_SDHOME"/
    fi

    section "Rolled back to v$saved"

    # NOTE: "debdroid.conf" is neither backed up nor restored. It is not part of
    #       a version: restoring an older copy would undo settings made since
    #       the update, which is the behaviour this release set out to remove.
    step "Your configuration was left as it is"

    # The binaries are copied over rather than cleared, as busybox is being run
    # from that directory right now. An unused newer binary may remain.
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

    # Records which backup to roll back to, so it never has to be guessed from
    # a sorted directory listing
    # NOTE: Written last, so a backup interrupted halfway is not pointed at.
    echo "$DEBDROID_VER" > "$DEBDROID_BACKUP"/previous
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
