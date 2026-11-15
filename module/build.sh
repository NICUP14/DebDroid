#!/bin/sh
# build.sh: Packages the flashable module zip
#
# The zip carries the whole payload plus module.prop and customize.sh, which
# the Magisk, KernelSU and APatch installers all read, so one zip covers every
# root solution.
#
# Two variants are published. The default is slim, a few megabytes, and the
# installer downloads the image on first install. "--with-image" bundles the
# image for installing on a device with no usable network, which costs about
# 150 megabytes.
#
# Usage: sh module/build.sh [--with-image] [output.zip]

MODULE_DIR=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REPO_DIR=${MODULE_DIR%/*}
OUTPUT=
WITH_IMAGE=no

while [ $# -gt 0 ]; do
    case "$1" in
        --with-image) WITH_IMAGE=yes ;;
        -h|--help)
            echo "Usage: sh module/build.sh [--with-image] [output.zip]"
            exit 0
            ;;
        -*)
            echo "Error: Unknown option: \"$1\"" >&2
            echo "Usage: sh module/build.sh [--with-image] [output.zip]" >&2
            exit 1
            ;;
        *) OUTPUT=$1 ;;
    esac
    shift
done

OUTPUT=${OUTPUT:-$REPO_DIR/out/DebDroid-module.zip}

# NOTE: The zip is created from inside the staging directory, so a relative
#       output path would resolve against that directory and be deleted along
#       with it, leaving no archive and no error. Resolved here instead.
case "$OUTPUT" in
    /*) ;;
    *)  OUTPUT=$PWD/$OUTPUT ;;
esac

if ! command -v zip > /dev/null 2>&1; then
    echo "Error: \"zip\" is required to build the module." >&2
    exit 1
fi

VERSION=$(sed -n "s/^export DEBDROID_VER='\(.*\)'$/\1/p" \
    "$REPO_DIR"/debdroid/debdroid_lib.sh)

if [ -z "$VERSION" ]; then
    echo "Error: Could not read DEBDROID_VER from debdroid/debdroid_lib.sh." >&2
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

# NOTE: Renamed on the way in, and this is not cosmetic. Magisk decides a zip is
#       an old-style "legacy" module purely by whether it contains a top-level
#       "install.sh":
#
#           is_legacy_script() {
#               unzip -l "$ZIPFILE" install.sh | grep -q install.sh
#           }
#
#       On a match it sources that file as the module's own install script and
#       never runs customize.sh, after extracting only module.prop, install.sh,
#       uninstall.sh and common/* into a scratch directory. DebDroid's installer
#       then ran with none of its payload beside it, failed its own file check,
#       and called "exit 1", which killed the whole installer because it had
#       been sourced rather than run. The module could not be flashed at all.
#       KernelSU and APatch share this installer lineage and the same check.
cp "$REPO_DIR"/install.sh "$STAGING"/debdroid-install.sh

# Stages the payload the app carries too, from the one list both read
# NOTE: The list includes rootfs/manifest, which carries the checksums the
#       image is verified against. The installer needs it whenever the image is
#       not bundled, which is exactly the case for a module built without img/.
# NOTE: Read line by line, and the staging is checked afterwards rather than
#       inside the loop, as the loop runs in a subshell and cannot exit the
#       script itself.
sed -e 's/#.*//' -e 's/[[:space:]]*$//' -e '/^$/d' "$MODULE_DIR"/payload.list |
while IFS= read -r entry; do
    if [ ! -e "$REPO_DIR/$entry" ]; then
        echo "Error: \"$entry\" is listed in module/payload.list but missing." >&2
        : > "$STAGING"/.payload-missing
        continue
    fi
    case "$entry" in
        */*) mkdir -p "$STAGING/${entry%/*}" ;;
    esac
    cp -r "$REPO_DIR/$entry" "$STAGING/$entry"
done
if [ -e "$STAGING"/.payload-missing ]; then
    rm -rf "$STAGING"
    exit 1
fi

# The image is bundled only when it is asked for, as it is the largest piece by
# far: it is the difference between a four megabyte zip and a 150 megabyte one
# NOTE: Presence is deliberately not the condition. img/ is always present in a
#       checkout, as the split parts are tracked in git, so keying off it would
#       bundle the image into every build including the slim one.
if [ "$WITH_IMAGE" = yes ]; then
    if [ ! -e "$REPO_DIR"/img/debian.img ] &&
            [ ! -e "$REPO_DIR"/img/debian.img.part-aa ]; then
        echo "Error: --with-image was given, but no image was found under" >&2
        echo "    \"$REPO_DIR/img\". Refusing to build an offline module" >&2
        echo "    that cannot install offline." >&2
        rm -rf "$STAGING"
        exit 1
    fi

    echo " -> Including the debian image"
    cp -r "$REPO_DIR"/img "$STAGING"/
else
    echo " -> Building without the image, the installer will download it"
fi

# Gives every entry one fixed timestamp, so two builds of the same tree produce
# the same bytes instead of differing by when they ran
# NOTE: SOURCE_DATE_EPOCH wins if it is set, which is the convention other
#       reproducible builds use. Otherwise the commit date, so the zip is tied
#       to what it was built from. The constant is only for a tree with neither,
#       such as an extracted tarball.
if [ -z "$SOURCE_DATE_EPOCH" ]; then
    SOURCE_DATE_EPOCH=$(git -C "$REPO_DIR" log -1 --format=%ct 2> /dev/null)
fi
: "${SOURCE_DATE_EPOCH:=1700000000}"

if stamp=$(date -u -d "@$SOURCE_DATE_EPOCH" +%Y%m%d%H%M.%S 2> /dev/null); then
    find "$STAGING" -exec touch -t "$stamp" {} +
else
    echo " -> Warning: could not set a fixed timestamp, the zip will not be" >&2
    echo "    byte-for-byte reproducible." >&2
fi

# NOTE: Checked rather than trusted. The installer is copied in under another
#       name above, and a later edit could undo that without any visible
#       symptom until someone tries to flash the zip, where the failure looks
#       like a broken installer rather than a naming collision.
if [ -e "$STAGING"/install.sh ]; then
    echo "Error: \"install.sh\" must not sit at the root of the module zip." >&2
    echo "    Magisk, KernelSU and APatch all treat a zip containing one as an" >&2
    echo "    old-style module: they source it and never run customize.sh, so" >&2
    echo "    the module cannot install. Ship it as \"debdroid-install.sh\"." >&2
    rm -rf "$STAGING"
    exit 1
fi

echo " -> Creating $OUTPUT"
rm -f "$OUTPUT"

# NOTE: Reproducible rather than "zip -r": the file list is sorted so entry
#       order does not depend on readdir, and -X drops the uid, gid and
#       high-precision timestamps that would otherwise vary per machine.
#       install.sh chmods the binaries itself, so no execute bit is lost.
if ! ( cd "$STAGING" &&
        find . -mindepth 1 ! -name '.*' -print |
        LC_ALL=C sort |
        zip -q -X -@ "$OUTPUT" ); then
    echo "Error: Failed to create \"$OUTPUT\"." >&2
    rm -rf "$STAGING"
    exit 1
fi

rm -rf "$STAGING"

# NOTE: Checked rather than assumed. A build that reports success while having
#       written nothing would let a release publish without the module, which
#       is how this was found in the first place.
if [ ! -s "$OUTPUT" ]; then
    echo "Error: \"$OUTPUT\" was not written." >&2
    exit 1
fi

echo " -> Checksum:"
sha256sum "$OUTPUT"

echo ":: Built $OUTPUT"
