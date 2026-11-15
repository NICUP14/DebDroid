#!/bin/sh
# test_update.sh: Checks what survives an update, and what must not
#
# Three things an update used to get wrong, each of which looked fine on a fresh
# install and only went wrong on the second one:
#
#   - the manager flags were edited in debdroid_mgr.sh, which an update replaces
#   - the version lived in debdroid_env.sh, which an update preserves, so it
#     froze at whatever was first installed
#   - rollback picked its backup with a lexical sort
#
# The first of those is now structural: debdroid.conf is not shipped, so the
# installer has no code path that writes it. The assertions below are what hold
# that in place, since there is no preserve list entry to read.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX

REPO_ROOT=${TESTS_DIR%/tests}

t_section "the conf file is never part of the payload"

# NOTE: This is the whole guarantee. A file that is never copied cannot be
#       overwritten by someone forgetting to list it as preserved.
t_false "no debdroid.conf ships in debdroid/" \
    test -e "$REPO_ROOT"/debdroid/debdroid.conf

if $BUSYBOX grep -qE '^\s*(environment\|debdroid_env\.sh|.*debdroid\.conf.*)\)' \
        "$REPO_ROOT"/install.sh &&
        $BUSYBOX grep -A3 'case "\$name" in' "$REPO_ROOT"/install.sh |
            "$BUSYBOX" grep -q 'debdroid\.conf'; then
    t_no "the preserve list does not mention debdroid.conf" \
        "listing it implies it ships, which is what this design avoids"
else
    t_ok "the preserve list does not mention debdroid.conf"
fi

# Any copy into the conf would reintroduce the overwrite, so only the migration
# may write it, and only with a redirection
if $BUSYBOX grep -nE '(cp|mv|install) [^|]*debdroid\.conf' "$REPO_ROOT"/install.sh; then
    t_no "the installer never copies anything onto debdroid.conf" \
        "a path above would overwrite the user's flags"
else
    t_ok "the installer never copies anything onto debdroid.conf"
fi

t_section "the manager reads the conf, but only when it exists"

if $BUSYBOX grep -q 'if \[ -f "\$DEBDROIDMGR_DIR/debdroid.conf" \]' \
        "$TEST_SD"/debdroid_mgr.sh; then
    t_ok "the manager sources debdroid.conf conditionally"
else
    t_no "the manager sources debdroid.conf conditionally" \
        "an unconditional source fails on every device without one"
fi

# The defaults have to stay in the manager, or it cannot run standalone, which
# is exactly how this suite invokes it
t_true "the defaults are still in the manager" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAP=no' "$TEST_SD"/debdroid_mgr.sh

# The conf is read after the defaults, or it could not override them
defaults_line=$($BUSYBOX grep -n '^CONFIG_DEV_FD=' "$TEST_SD"/debdroid_mgr.sh |
    $BUSYBOX cut -d: -f1)
conf_line=$($BUSYBOX grep -n 'DEBDROIDMGR_DIR/debdroid.conf' "$TEST_SD"/debdroid_mgr.sh |
    $BUSYBOX head -n 1 | $BUSYBOX cut -d: -f1)
if [ -n "$defaults_line" ] && [ -n "$conf_line" ] &&
        [ "$conf_line" -gt "$defaults_line" ]; then
    t_ok "the conf is read after the defaults, so it overrides them"
else
    t_no "the conf is read after the defaults, so it overrides them" \
        "defaults at line $defaults_line, conf at line $conf_line"
fi

t_section "migrating flags out of an older manager"

eval "$(sed -n '/^config_value()/,/^}/p' "$REPO_ROOT"/install.sh)"
eval "$(sed -n '/^migrate_manager_flags()/,/^}/p' "$REPO_ROOT"/install.sh)"

MIG="$TEST_TMP"/migrate
mkdir -p "$MIG"

# What this release ships, comments and an empty value included
cat > "$MIG"/new_mgr.sh << 'NEWMGR'
#!/system/bin/sh
# Debdroid Manager configuration parameters
CONFIG_DEV_FD=yes
CONFIG_ENV_SWAP=no
CONFIG_ENV_SWAPPINESS=60 # How eagerly the kernel swaps [0 - 100]
CONFIG_ENV_SWAP_PRIO=    # Swap priority, empty places it below any zram
CONFIG_ENV_GONE=yes
start_environment() { :; }
NEWMGR

# An installed manager from before the split, with two flags changed
cat > "$MIG"/old_mgr.sh << 'OLDMGR'
#!/system/bin/sh
# Debdroid Manager configuration parameters
CONFIG_DEV_FD=yes
CONFIG_ENV_SWAP=yes
CONFIG_ENV_SWAPPINESS=10 # How eagerly the kernel swaps [0 - 100]
CONFIG_ENV_SWAP_PRIO=    # Swap priority, empty places it below any zram
# not a flag
start_environment() { :; }
OLDMGR

migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/old_mgr.sh \
    "$MIG"/new_mgr.sh > /dev/null 2>&1

t_true "it creates the conf" test -f "$MIG"/debdroid.conf
t_true "it carries a changed flag over" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAP=yes$' "$MIG"/debdroid.conf
t_true "it carries a changed number over, without its comment" \
    "$BUSYBOX" grep -q '^CONFIG_ENV_SWAPPINESS=10$' "$MIG"/debdroid.conf
t_false "it copies nothing but flags" \
    "$BUSYBOX" grep -q 'start_environment' "$MIG"/debdroid.conf

# NOTE: This is the flaw the published zip exposed. Copying the whole block
#       wrote out flags identical to the defaults, which pinned them: a later
#       release improving one would have been overridden by a file the user
#       never created.
t_false "it leaves a flag that still matches the default" \
    "$BUSYBOX" grep -q 'CONFIG_DEV_FD' "$MIG"/debdroid.conf
t_false "an empty value that matches the default is left too" \
    "$BUSYBOX" grep -q 'CONFIG_ENV_SWAP_PRIO' "$MIG"/debdroid.conf
t_false "a flag this version dropped is not carried over" \
    "$BUSYBOX" grep -q 'CONFIG_ENV_GONE' "$MIG"/debdroid.conf
t_eq "so only the two changed flags are written" \
    "2" "$("$BUSYBOX" grep -c '^CONFIG_' "$MIG"/debdroid.conf)"

# An install whose flags are all stock needs no conf at all
rm -f "$MIG"/debdroid.conf
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/new_mgr.sh \
    "$MIG"/new_mgr.sh > /dev/null 2>&1
t_false "an untouched install produces no conf" test -e "$MIG"/debdroid.conf

# Running again must not touch what the user has since edited
echo 'CONFIG_ENV_SWAP=no' > "$MIG"/debdroid.conf
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/old_mgr.sh \
    "$MIG"/new_mgr.sh > /dev/null 2>&1
t_eq "an existing conf is left alone" \
    "CONFIG_ENV_SWAP=no" "$(cat "$MIG"/debdroid.conf)"

# A fresh install has no old manager to read
rm -f "$MIG"/debdroid.conf
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/absent.sh \
    "$MIG"/new_mgr.sh > /dev/null 2>&1
t_false "a fresh install produces no conf" test -e "$MIG"/debdroid.conf

# A manager with no flags at all leaves nothing behind
echo '#!/system/bin/sh' > "$MIG"/flagless.sh
migrate_manager_flags "$MIG"/debdroid.conf "$MIG"/flagless.sh \
    "$MIG"/new_mgr.sh > /dev/null 2>&1
t_false "a manager with no flags produces no conf" test -e "$MIG"/debdroid.conf

t_section "rollback restores the version it recorded"

# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_ui.sh

# The updater refuses to run over a live environment; that is covered elsewhere
require_idle() { return 0; }

DEBDROID_BACKUP="$TEST_TMP"/backups
DEBDROID_SDHOME="$TEST_TMP"/rollback_sd
DEBDROID_BIN="$TEST_TMP"/rollback_bin
DEBDROID_LIB="$TEST_TMP"/rollback_lib

eval "$(sed -n '/^rollback_update()/,/^}/p' "$TEST_SD"/debdroid_update.sh)"

# Lays out two backups whose names sort the wrong way round, which is the bug:
# "ls | tail -n 1" picked 1.2.9 because it sorts after 1.2.10
rollback_fixture()
{
    rm -rf "$DEBDROID_BACKUP" "$DEBDROID_SDHOME" "$DEBDROID_BIN" "$DEBDROID_LIB"
    mkdir -p "$DEBDROID_SDHOME" "$DEBDROID_BIN" "$DEBDROID_LIB"

    for version in 1.2.9 1.2.10; do
        mkdir -p "$DEBDROID_BACKUP/$version"/scripts
        for needed in debdroid.sh debdroid_mgr.sh debdroid_env.sh \
            debdroid_ui.sh debdroid_lib.sh; do
            echo "# v$version" > "$DEBDROID_BACKUP/$version/scripts/$needed"
        done
    done

    echo "1.2.10" > "$DEBDROID_BACKUP"/previous
}

rollback_fixture
rollback_update > /dev/null 2>&1
t_eq "it restores the recorded version, not the one that sorts last" \
    "# v1.2.10" "$(cat "$DEBDROID_SDHOME"/debdroid.sh)"

# A backup taken before the pointer existed still has to roll back
rollback_fixture
rm -f "$DEBDROID_BACKUP"/previous
rollback_update > /dev/null 2>&1
t_true "it still works without a pointer file" test -f "$DEBDROID_SDHOME"/debdroid.sh

# A pointer naming a backup that is gone must not stop the rollback
rollback_fixture
echo "9.9.9" > "$DEBDROID_BACKUP"/previous
rollback_update > /dev/null 2>&1
t_true "a stale pointer falls back to a real backup" \
    test -f "$DEBDROID_SDHOME"/debdroid.sh

t_section "rollback cannot take user data with it"

rollback_fixture
# Everything a rollback must leave alone, alongside a stale script it must clear
echo "keep me" > "$DEBDROID_SDHOME"/environment
echo "CONFIG_ENV_SWAP=yes" > "$DEBDROID_SDHOME"/debdroid.conf
echo "manifest" > "$DEBDROID_SDHOME"/rootfs-manifest
mkdir -p "$DEBDROID_SDHOME"/img "$DEBDROID_SDHOME"/command
echo "the image" > "$DEBDROID_SDHOME"/img/debian.img
echo "# added by a newer version" > "$DEBDROID_SDHOME"/debdroid_newthing.sh

rollback_update > /dev/null 2>&1

t_eq "it keeps environment" "keep me" "$(cat "$DEBDROID_SDHOME"/environment)"
t_eq "it keeps debdroid.conf" \
    "CONFIG_ENV_SWAP=yes" "$(cat "$DEBDROID_SDHOME"/debdroid.conf)"
t_eq "it keeps the debian image" \
    "the image" "$(cat "$DEBDROID_SDHOME"/img/debian.img)"
t_true "it keeps rootfs-manifest" test -f "$DEBDROID_SDHOME"/rootfs-manifest
t_true "it keeps the command directory" test -d "$DEBDROID_SDHOME"/command
t_false "it clears a script the newer version added" \
    test -e "$DEBDROID_SDHOME"/debdroid_newthing.sh

# An incomplete backup must not clear the scripts, or recovery leaves nothing
rollback_fixture
rm -f "$DEBDROID_BACKUP"/1.2.10/scripts/debdroid_mgr.sh
echo "# current" > "$DEBDROID_SDHOME"/debdroid_mgr.sh
rollback_update > /dev/null 2>&1
t_true "an incomplete backup leaves the current scripts in place" \
    test -f "$DEBDROID_SDHOME"/debdroid_mgr.sh

t_section "the backup records which version it holds"

eval "$(sed -n '/^backup_installation()/,/^}/p' "$TEST_SD"/debdroid_update.sh)"

rollback_fixture
# shellcheck disable=SC2034
# DEBDROID_VER is read by backup_installation, loaded from the product script.
DEBDROID_VER=1.2.11
echo "# current" > "$DEBDROID_SDHOME"/debdroid.sh
backup_installation > /dev/null 2>&1

t_eq "backup_installation records the version it saved" \
    "1.2.11" "$(cat "$DEBDROID_BACKUP"/previous 2> /dev/null)"
t_true "and the backup it points at exists" \
    test -d "$DEBDROID_BACKUP"/1.2.11

# Round trip: the pointer that was just written is the one rollback follows
rollback_update > /dev/null 2>&1
t_eq "rollback follows the pointer backup_installation wrote" \
    "# current" "$(cat "$DEBDROID_SDHOME"/debdroid.sh)"

t_section "the version is not in the file the installer preserves"

t_true "the version is in debdroid_lib.sh" \
    "$BUSYBOX" grep -q "^export DEBDROID_VER='" "$REPO_ROOT"/debdroid/debdroid_lib.sh
t_false "the version is not in debdroid_env.sh" \
    "$BUSYBOX" grep -q '^export DEBDROID_VER=' "$REPO_ROOT"/debdroid/debdroid_env.sh

# NOTE: A device updated from an older release still has the old value in its
#       preserved debdroid_env.sh. Every entry point sources env before lib, so
#       the live value must be the one from lib.
# NOTE: Run as a separate process rather than a subshell, so neither the stale
#       file nor the library can leak into this one, and so the result is read
#       from the child's output instead of from "$?", which is easy to read off
#       the wrong command.
stale_env="$TEST_TMP"/stale_env.sh
echo "export DEBDROID_VER='1.0.0'" > "$stale_env"

shipped_version=$("$BUSYBOX" sed -n "s/^export DEBDROID_VER='\(.*\)'\$/\1/p" \
    "$REPO_ROOT"/debdroid/debdroid_lib.sh)
live_version=$(sh -c '. "$1" && . "$2" && echo "$DEBDROID_VER"' \
    sh "$stale_env" "$REPO_ROOT"/debdroid/debdroid_lib.sh 2>/dev/null)

t_eq "lib overrides a stale version left in a preserved env file" \
    "$shipped_version" "$live_version"

for entry in debdroid.sh debdroid_update.sh debdroid_uninstall.sh \
    debdroid_resize.sh debdroid_swap.sh debdroid_image.sh debdroid_probe.sh; do
    order=$($BUSYBOX grep -n 'debdroid_env\.sh\|debdroid_lib\.sh' \
        "$REPO_ROOT"/debdroid/"$entry" |
        $BUSYBOX grep '\. ' | $BUSYBOX sed -n 's/.*\(debdroid_[a-z]*\)\.sh.*/\1/p' |
        $BUSYBOX tr '\n' ' ')
    case "$order" in
        *debdroid_env*debdroid_lib*)
            t_ok "$entry sources env before lib" ;;
        *)
            t_no "$entry sources env before lib" \
                "got: $order, so a stale version would win" ;;
    esac
done

t_summary
