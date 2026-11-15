#!/bin/sh
# test_probe.sh: Checks the machine-readable probe behind "debdroid.sh probe"
#
# The probe is a contract with programs, the app first among them, so the
# format is tested as strictly as the facts. Most checks are about devices in a
# state the app must recognise without being told: nothing installed, an
# interrupted download, binaries that lost their execute bit, an architecture
# with no binaries built.

# shellcheck disable=SC2034
# BUSYBOX is read by the product scripts this file runs, not here.

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX
TEST_HOME="$TEST_TMP"/home
TEST_IMG="$TEST_SD"/img/debian.img

# Runs the installed probe
run_probe()
{
    sh "$TEST_SD"/debdroid_probe.sh 2> /dev/null
}

# Prints the value of one key from a probe report
# Usage: probe_value <key> <report>
probe_value()
{
    echo "$2" | sed -n "s/^$1=//p"
}

# Writes a file carrying the ext2 superblock magic, as a stand-in image
make_ext2_stub()
{
    mkdir -p "${1%/*}"
    dd if=/dev/zero of="$1" bs=1024 count=4 2> /dev/null
    printf '\123\357' | dd of="$1" bs=1 seek=1080 conv=notrunc 2> /dev/null
}

rm -rf "${TEST_HOME:?}"/bin "${TEST_SD:?}"/img
mkdir -p "$TEST_HOME"

t_section "the report is a contract"

report=$(run_probe)
status=$?

t_eq "it exits 0" 0 "$status"
t_eq "it names its format version first" "probe=1" "$(echo "$report" | head -n 1)"

bad_lines=$(echo "$report" | grep -vE '^[a-z_]+=' || true)
t_eq "every line is key=value" "" "$bad_lines"

dup_keys=$(echo "$report" | sed 's/=.*//' | sort | uniq -d)
t_eq "no key is printed twice" "" "$dup_keys"

for key in running pid mounts processes swap wakelock version image_size \
    image_present binaries_ok module_installed started services mount_ns; do
    if echo "$report" | grep -q "^$key="; then
        t_ok "it reports \"$key\""
    else
        t_no "it reports \"$key\"" "$report"
    fi
done

shipped_version=$(sed -n "s/^export DEBDROID_VER='\(.*\)'\$/\1/p" "$TEST_SD"/debdroid_lib.sh)
t_eq "version is the probing copy's own" "$shipped_version" "$(probe_value version "$report")"
t_eq "installed_version reads the installed scripts" \
    "$shipped_version" "$(probe_value installed_version "$report")"

t_section "a device with nothing set up"

t_eq "root is reported" yes "$(probe_value root "$report")"
t_eq "busybox is found" ok "$(probe_value busybox "$report")"
t_eq "the arm64 binaries are recognised as built" yes "$(probe_value arch_built "$report")"
t_eq "no binaries are installed" no "$(probe_value binaries_present "$report")"
t_eq "no image is present" no "$(probe_value image_present "$report")"
t_eq "an absent image has no size" "" "$(probe_value image_size "$report")"
t_eq "an absent image is not usable" no "$(probe_value image_ok "$report")"
t_eq "nothing is running" no "$(probe_value running "$report")"
t_eq "no session holds the image" none "$(probe_value session "$report")"
t_eq "no pid is invented" "" "$(probe_value pid "$report")"

# NOTE: The lock lives beside the mount point. Opening it to test it used to
#       read a missing directory as a session holding the lock, which would
#       have shown a device with nothing installed as busy.
t_false "the probe creates no lock file" test -e "$TEST_HOME"/mnt.lock

t_section "root, and architectures without binaries"

report=$(FAKE_UID=1000 run_probe)
t_eq "a non-root caller is reported as such" no "$(probe_value root "$report")"

report=$(FAKE_ABI=armeabi-v7a run_probe)
t_eq "armv7 maps to armhf" armhf "$(probe_value arch "$report")"
t_eq "but has no binaries built, so the app can refuse it early" \
    no "$(probe_value arch_built "$report")"

report=$(FAKE_ABI=mips run_probe)
t_eq "an unknown abi is still reported" mips "$(probe_value abi "$report")"
t_eq "with no architecture" "" "$(probe_value arch "$report")"

t_section "the image"

make_ext2_stub "$TEST_IMG"
report=$(run_probe)
t_eq "a present image is found" yes "$(probe_value image_present "$report")"
t_eq "its size is reported in bytes" 4096 "$(probe_value image_size "$report")"
t_eq "the ext2 magic marks it usable" yes "$(probe_value image_ok "$report")"
t_eq "with no partial download" no "$(probe_value image_partial "$report")"

dd if=/dev/zero of="$TEST_IMG" bs=1024 count=4 2> /dev/null
report=$(run_probe)
t_eq "a file without the magic is not usable" no "$(probe_value image_ok "$report")"

rm -f "$TEST_IMG"
: > "$TEST_IMG".download.gz
report=$(run_probe)
t_eq "a leftover compressed download is reported" yes "$(probe_value image_partial "$report")"
rm -f "$TEST_IMG".download.gz

: > "$TEST_IMG".download
report=$(run_probe)
t_eq "a leftover unpacked download is reported" yes "$(probe_value image_partial "$report")"
rm -f "$TEST_IMG".download

t_section "the binaries"

mkdir -p "$TEST_HOME"/bin
for bin in busybox e2fsck resize2fs; do
    echo "#!/bin/sh" > "$TEST_HOME/bin/$bin"
    chmod 644 "$TEST_HOME/bin/$bin"
done

# NOTE: This is the failure class a flash at 0644 produces, and the reason the
#       app offers a repair rather than a reinstall.
report=$(run_probe)
if [ -x "$TEST_HOME"/bin/e2fsck ]; then
    t_skip "binaries without the execute bit are reported" \
        "this filesystem cannot clear the execute bit"
else
    t_eq "binaries without the execute bit are present" \
        yes "$(probe_value binaries_present "$report")"
    t_eq "but not usable" no "$(probe_value binaries_ok "$report")"
fi

chmod 755 "$TEST_HOME"/bin/*
report=$(run_probe)
t_eq "executable binaries are usable" yes "$(probe_value binaries_ok "$report")"
rm -rf "${TEST_HOME:?}"/bin

t_section "a copy that is not installed"

# NOTE: The app runs the probe out of its own payload before anything is on the
#       device, so the probe must stand on the files beside it and on a busybox
#       that arrived at 0644, as everything extracted from a zip does.
bundle="$TEST_TMP"/bundle
rm -rf "$bundle"
mkdir -p "$bundle"/debdroid "$bundle"/bin
cp "$TEST_SD"/debdroid_env.sh "$TEST_SD"/debdroid_ui.sh "$TEST_SD"/debdroid_lib.sh \
    "$TEST_SD"/debdroid_probe.sh "$bundle"/debdroid/
cp "$FAKE_BUSYBOX" "$bundle"/bin/busybox
chmod 644 "$bundle"/bin/busybox

# Points the bundle's own env file at an installed busybox that does not exist,
# and the probe away from the installed env file, so the bundled busybox is the
# only one it can find
sed -i -e "s#^export BUSYBOX=.*#export BUSYBOX=$TEST_TMP/nonexistent/busybox#" \
    "$bundle"/debdroid/debdroid_env.sh
sed -i -e "s#$TEST_SD/debdroid_env.sh#$TEST_TMP/nonexistent/debdroid_env.sh#" \
    "$bundle"/debdroid/debdroid_probe.sh

report=$(sh "$bundle"/debdroid/debdroid_probe.sh 2> /dev/null)
status=$?

t_eq "it exits 0" 0 "$status"
t_eq "it finds the bundled busybox" ok "$(probe_value busybox "$report")"
if [ -x "$FAKE_BUSYBOX" ] && ! [ -x "$bundle"/bin/busybox ]; then
    t_no "it restores the execute bit on the bundled busybox" \
        "$(ls -l "$bundle"/bin/busybox)"
else
    t_ok "it restores the execute bit on the bundled busybox"
fi

rm -f "$bundle"/bin/busybox
report=$(sh "$bundle"/debdroid/debdroid_probe.sh 2> /dev/null)
status=$?
t_eq "with no busybox anywhere it still exits 0" 0 "$status"
t_eq "and says busybox is missing" missing "$(probe_value busybox "$report")"
if echo "$report" | grep -q '^root='; then
    t_ok "and still reports root"
else
    t_no "and still reports root" "$report"
fi

t_section "the frontend"

report=$(sh "$TEST_SD"/debdroid.sh probe 2> /dev/null)
t_eq "\"debdroid.sh probe\" runs the probe" "probe=1" "$(echo "$report" | head -n 1)"

t_summary
