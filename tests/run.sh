#!/bin/sh
# run.sh: Runs the host test suite, which needs neither root nor a device
#
# Each tests/test_*.sh file runs as its own process and exits non-zero when one
# of its checks failed. Product scripts are never modified: the suite copies
# them into a temporary directory and rewrites the hardcoded device paths.
#
# Usage: sh tests/run.sh [name...]   (names select individual test files)

TESTS_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
export TESTS_DIR
REPO_DIR=${TESTS_DIR%/*}
export REPO_DIR

# Builds a scratch directory that mimics an installed DebDroid
# Sets "TEST_TMP" to its path, removed by the trap below
TEST_TMP=$(mktemp -d) || exit 1
export TEST_TMP
trap 'rm -rf "$TEST_TMP"' EXIT

# Installs a fake busybox that forwards applets to the host tools
# NOTE: "id" does not report the same way on a build machine, so it is emulated;
#       FAKE_UID lets a test change what it reports.
# NOTE: getprop is deliberately NOT emulated here. It is an android toolbox
#       binary and never a busybox applet, so faking it as one let
#       "$BUSYBOX getprop" pass every host test while failing on every real
#       device. An applet this stub does not know now falls through to the host,
#       where there is no getprop, so that regression fails here too.
cat > "$TEST_TMP"/busybox <<'STUB'
#!/bin/sh
applet=$1
shift
case "$applet" in
    id)
        if [ "$1" = "-u" ]; then echo "${FAKE_UID-0}"; else exec id "$@"; fi
        ;;
    *)
        exec "$applet" "$@"
        ;;
esac
STUB
chmod 755 "$TEST_TMP"/busybox
export FAKE_BUSYBOX="$TEST_TMP"/busybox

# Stands in for the android property service, which no build machine has
# NOTE: get_prop tries /system/bin/getprop first and falls back to a PATH
#       lookup, which is the hook this uses. FAKE_ABI and FAKE_ABILIST let a
#       test change what the device appears to be.
cat > "$TEST_TMP"/getprop <<'STUB'
#!/bin/sh
case "$1" in
    ro.product.cpu.abi)     echo "${FAKE_ABI-arm64-v8a}" ;;
    ro.product.cpu.abilist) echo "${FAKE_ABILIST-}" ;;
    *)                      echo "" ;;
esac
STUB
chmod 755 "$TEST_TMP"/getprop
PATH="$TEST_TMP:$PATH"
export PATH

# Copies the product scripts and repoints the paths baked into them
mkdir -p "$TEST_TMP"/sd "$TEST_TMP"/home
cp "$REPO_DIR"/debdroid/*.sh "$TEST_TMP"/sd/
sed -i \
    -e "s#/sdcard/debdroid#$TEST_TMP/sd#g" \
    -e "s#^export DEBDROID_HOME=.*#export DEBDROID_HOME=$TEST_TMP/home#" \
    -e "s#^export BUSYBOX=.*#export BUSYBOX=$TEST_TMP/busybox#" \
    "$TEST_TMP"/sd/*.sh
export TEST_SD="$TEST_TMP"/sd

# Selects the requested test files, or every one of them
if [ $# -gt 0 ]; then
    selected=
    for name in "$@"; do
        selected="$selected $TESTS_DIR/test_$name.sh"
    done
else
    selected=$(echo "$TESTS_DIR"/test_*.sh)
fi

files=0
failed=0
for file in $selected; do
    if [ ! -f "$file" ]; then
        echo "Error: No such test file: $file" >&2
        exit 1
    fi

    files=$((files + 1))
    echo "===== ${file##*/} ====="
    sh "$file" || failed=$((failed + 1))
    echo
done

if [ "$failed" -eq 0 ]; then
    echo "All $files test file(s) passed."
    exit 0
fi

echo "$failed of $files test file(s) failed." >&2
exit 1
