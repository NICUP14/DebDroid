#!/bin/sh
# build.sh: Packages the flashable module zip
#
# The zip carries the whole payload plus module.prop and customize.sh, which
# the Magisk, KernelSU and APatch installers all read, so one zip covers every
# root solution.
#
# Usage: sh module/build.sh [output.zip]

MODULE_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${MODULE_DIR%/*}
OUTPUT=${1:-$REPO_DIR/out/DebDroid-module.zip}

if ! command -v zip > /dev/null 2>&1; then
    echo "Error: \"zip\" is required to build the module." >&2
    exit 1
fi

VERSION=$(sed -n "s/^export DEBDROID_VER='\(.*\)'$/\1/p" \
    "$REPO_DIR"/debdroid/debdroid_env.sh)

if [ -z "$VERSION" ]; then
    echo "Error: Could not read DEBDROID_VER from debdroid/debdroid_env.sh." >&2
    exit 1
fi

# The module version has to track the runtime version, or an update check lies
if ! grep -q "^version=v$VERSION$" "$MODULE_DIR"/module.prop; then
    echo "Error: module.prop says $(grep '^version=' "$MODULE_DIR"/module.prop)," >&2
    echo "    but DEBDROID_VER is $VERSION. Update module.prop." >&2
    exit 1
fi

echo ":: Building the DebDroid module v$VERSION"

STAGING=$REPO_DIR/out/module-staging
rm -rf "$STAGING"
mkdir -p "$STAGING" "${OUTPUT%/*}"

echo " -> Collecting the payload"
cp "$MODULE_DIR"/module.prop "$MODULE_DIR"/customize.sh "$STAGING"/
cp "$REPO_DIR"/install.sh "$STAGING"/
cp -r "$REPO_DIR"/debdroid "$REPO_DIR"/command "$REPO_DIR"/bin "$REPO_DIR"/lib \
      "$REPO_DIR"/tests "$REPO_DIR"/boot "$STAGING"/

# NOTE: The manifest carries the checksums the image is verified against. The
#       installer needs it whenever the image is not bundled, which is exactly
#       the case for a module built without img/.
mkdir -p "$STAGING"/rootfs
cp "$REPO_DIR"/rootfs/manifest "$STAGING"/rootfs/manifest

# The image is only included when it is present, as it is the largest piece and
# newer installs fetch a rootfs instead
if [ -d "$REPO_DIR"/img ]; then
    echo " -> Including the debian image"
    cp -r "$REPO_DIR"/img "$STAGING"/
else
    echo " -> No img/ directory, the module will install without an image"
fi

cp "$REPO_DIR"/LICENSE "$REPO_DIR"/NOTICE "$REPO_DIR"/THIRD_PARTY.md "$STAGING"/
cp -r "$REPO_DIR"/licenses "$STAGING"/

echo " -> Creating $OUTPUT"
rm -f "$OUTPUT"
( cd "$STAGING" && zip -q -r "$OUTPUT" . -x '.*' )

rm -rf "$STAGING"

echo " -> Checksum:"
sha256sum "$OUTPUT"

echo ":: Built $OUTPUT"
