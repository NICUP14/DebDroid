#!/bin/sh
# test_lib.sh: Checks the shared helpers in debdroid_lib.sh

. "$TESTS_DIR"/assert.sh
test_setup_colors

BUSYBOX=$FAKE_BUSYBOX
# shellcheck disable=SC1091
. "$TEST_SD"/debdroid_lib.sh

t_section "shell_quote round-trips every argument"

# Quotes the arguments, then evaluates the result back into a bracketed list
# NOTE: A correct quoting keeps the argument count and contents unchanged.
quote_roundtrip()
{
    label=$1
    expected=$2
    shift 2

    shell_quote "$@"
    actual=$(eval "for a in $DEBDROID_QUOTED; do printf '[%s]' \"\$a\"; done")
    t_eq "$label" "$expected" "$actual"
}

quote_roundtrip "plain words"        '[apt][update]'      apt update
quote_roundtrip "embedded space"     '[hello world]'      'hello world'
quote_roundtrip "single quote"       "[a'b]"              "a'b"
quote_roundtrip "only quotes"        "[''']"              "'''"
quote_roundtrip "variable reference" '[$HOME]'            '$HOME'
quote_roundtrip "glob"               '[*]'                '*'
quote_roundtrip "backticks"          '[`id`]'             '`id`'
quote_roundtrip "command expansion"  '[$(id)]'            '$(id)'
quote_roundtrip "double quote"       '[a"b]'              'a"b'
quote_roundtrip "backslash"          '[\]'                '\'
quote_roundtrip "semicolon"          '[;rm -rf /]'        ';rm -rf /'
quote_roundtrip "pipe"               '[a|b]'              'a|b'
quote_roundtrip "tilde"              '[~]'                '~'
quote_roundtrip "empty argument"     '[a][][b]'           a '' b
quote_roundtrip "repeated spaces"    '[a  b]'             'a  b'
quote_roundtrip "mixed quotes"       "[it's a \"test\"]"  "it's a \"test\""
quote_roundtrip "flag with space"    '[--flag=x y]'       '--flag=x y'

# A newline has to survive too, as it would otherwise split the command
shell_quote 'a
b'
actual=$(eval "printf '[%s]' $DEBDROID_QUOTED")
t_eq "embedded newline" '[a
b]' "$actual"

shell_quote
t_eq "no arguments yields an empty string" "" "$DEBDROID_QUOTED"

t_section "check_size accepts the documented grammar"

for size in 512M 5G 10K 1G 0G 05G 999999G; do
    t_true "accepts \"$size\"" check_size "$size"
done

# NOTE: A bare number must be rejected, as resize2fs would read it as blocks.
for size in '' 5 1024 M G 5m 5g 5GG M5 -5G '5 G' '5G5' 5MB 1.5G ' 5G' '5G '; do
    t_false "rejects \"$size\"" check_size "$size"
done

t_section "check_arch maps the android abi"

# NOTE: The stub busybox runs as its own process, so the fake properties have to
#       be exported rather than merely assigned.
export FAKE_ABI FAKE_ABILIST

FAKE_ABI=arm64-v8a
check_arch
t_eq "arm64-v8a maps to arm64" "arm64" "$DEBDROID_ARCH"

FAKE_ABI=armeabi-v7a
t_false "armeabi-v7a is not supported yet" check_arch
check_arch
t_eq "an unsupported abi clears the arch" "" "$DEBDROID_ARCH"

FAKE_ABI=x86_64
t_false "x86_64 is not supported yet" check_arch

# Very old devices report only the abi list, so the first entry is used
FAKE_ABI=
FAKE_ABILIST=arm64-v8a,armeabi-v7a,armeabi
check_arch
t_eq "falls back to the abi list" "arm64" "$DEBDROID_ARCH"

FAKE_ABI=
FAKE_ABILIST=
t_false "no abi at all fails" check_arch

t_section "find_busybox locates a usable binary"

BUSYBOX=$FAKE_BUSYBOX
find_busybox
t_eq "keeps an executable BUSYBOX" "$FAKE_BUSYBOX" "$BUSYBOX"

# The contract is that success always leaves BUSYBOX executable, whether it kept
# the given value or found one of the install locations
BUSYBOX=$TEST_TMP/does-not-exist
if find_busybox; then
    t_true "success implies an executable BUSYBOX" test -x "$BUSYBOX"
else
    t_ok "failure is reported when no busybox can be found"
fi

t_summary
