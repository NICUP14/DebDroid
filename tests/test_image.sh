#!/bin/sh
# test_image.sh: Checks how the prebuilt debian image is located and verified
#
# This is the code that decides whether a few hundred megabytes about to become
# the root filesystem is genuine, so the rejections matter more than the happy
# path.

# shellcheck disable=SC2034
# shellcheck disable=SC2154
# BUSYBOX and the manifest path are read by the functions loaded below, which
# in turn set image_sha and image_size.

. "$TESTS_DIR"/assert.sh
test_setup_colors

# NOTE: Empty BUSYBOX makes "$BUSYBOX awk" fall through to the host's awk,
#       which is how the harness exercises the real code without an android
#       binary.
BUSYBOX=
DEBDROIDIMG_MANIFEST="$TEST_TMP"/rootfs-manifest

eval "$(sed -n '/^read_manifest()/,/^}/p' "$REPO_DIR"/debdroid/debdroid_image.sh)"

t_section "read_manifest finds the entry for an architecture"

cat > "$DEBDROIDIMG_MANIFEST" <<'EOF'
# A comment, and the line below is indented padding
   # another comment
default-arm64 | IMAGE | arm64 | bookworm | aaaa1111 | 1073741824 | git:img/x | features=ext_attr | built=2025-09-02
bookworm-armhf | TARBALL | armhf | bookworm | bbbb2222 | 500 | https://example/x.tar.gz
EOF

DEBDROID_ARCH=arm64
if read_manifest; then
    t_eq "it reads the checksum" "aaaa1111" "$image_sha"
    t_eq "it reads the size" "1073741824" "$image_size"
else
    t_no "it finds the arm64 image" "read_manifest returned non-zero"
fi

# A TARBALL entry is not an image, so it must not be picked up as one
DEBDROID_ARCH=armhf
t_false "a tarball entry is not treated as an image" read_manifest

DEBDROID_ARCH=amd64
t_false "an architecture with no entry fails" read_manifest

t_section "read_manifest tolerates a hand-edited manifest"

# NOTE: The manifest is edited by hand, so the parsing must not depend on an
#       exact amount of whitespace around each field.
cat > "$DEBDROIDIMG_MANIFEST" <<'EOF'
tight|IMAGE|arm64|bookworm|cccc3333|999|git:img/x
EOF
DEBDROID_ARCH=arm64
read_manifest
t_eq "no spaces around the separators" "cccc3333" "$image_sha"

printf 'loose   |   IMAGE   |   arm64   |   bookworm   |   dddd4444   |   888   | git:x\n' \
    > "$DEBDROIDIMG_MANIFEST"
read_manifest
t_eq "extra spaces around the separators" "dddd4444" "$image_sha"

t_section "read_manifest refuses to trust an unfinished manifest"

# An unfilled entry must fail closed rather than install something unverified
cat > "$DEBDROIDIMG_MANIFEST" <<'EOF'
future | IMAGE | arm64 | trixie | PLACEHOLDER | 0 | https://example/x
EOF
DEBDROID_ARCH=arm64
t_false "a PLACEHOLDER checksum is rejected" read_manifest

cat > "$DEBDROIDIMG_MANIFEST" <<'EOF'
broken | IMAGE | arm64 | bookworm
EOF
t_false "a truncated record is rejected" read_manifest

cat > "$DEBDROIDIMG_MANIFEST" <<'EOF'
# every entry is commented out
# default-arm64 | IMAGE | arm64 | bookworm | aaaa1111 | 10 | git:x
EOF
t_false "a fully commented manifest is rejected" read_manifest

: > "$DEBDROIDIMG_MANIFEST"
t_false "an empty manifest is rejected" read_manifest

rm -f "$DEBDROIDIMG_MANIFEST"
t_false "a missing manifest is rejected" read_manifest

t_section "the shipped manifest describes the shipped image"

# The real manifest has to match the image actually in the repository, or the
# installer would reject the very image it ships with
DEBDROIDIMG_MANIFEST="$REPO_DIR"/rootfs/manifest
DEBDROID_ARCH=arm64

if read_manifest; then
    t_ok "the shipped manifest has an arm64 image entry"

    if [ -f "$REPO_DIR"/img/debian.img.part-aa ]; then
        # Summing the parts is cheap, so the size is always checked
        actual_size=$(cat "$REPO_DIR"/img/debian.img.part-* | wc -c | tr -d ' ')
        t_eq "its size matches the assembled parts" "$image_size" "$actual_size"

        # NOTE: Hashing a gigabyte takes seconds, which is a poor trade in a
        #       suite that otherwise runs instantly. tools/verify-provenance.sh
        #       does it as a release gate instead.
        t_skip "its checksum matches the assembled parts" \
            "checked by \"make provenance\", which hashes the whole image"
    else
        t_skip "its size matches the assembled parts" \
            "the image is not in the repository, so there is nothing to compare"
    fi
else
    t_skip "the shipped manifest has an arm64 image entry" \
        "no verified image is published yet"
fi

t_summary
