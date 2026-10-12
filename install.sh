#!/system/bin/sh

echo ' ____  _____ ____  ____  ____   ___ ___ ____  '
echo '|  _ \| ____| __ )|  _ \|  _ \ / _ \_ _|  _ \ '
echo '| | | |  _| |  _ \| | | | |_) | | | | || | | |'
echo '| |_| | |___| |_) | |_| |  _ <| |_| | || |_| |'
# shellcheck disable=SC2028
echo '|____/|_____|____/|____/|_| \_\\\\___/___|____/ '
echo
echo 'DebDroid installer (https://github.com/NICUP14/DebDroid)'
echo 'Author: NICUP14'
echo

# Checks for the required debdroid files
for file in debdroid_env.sh debdroid_ui.sh; do
    if [ ! -f ./debdroid/"$file" ]; then
        echo "Error: Missing required file: ./debdroid/$file" >&2
        echo "    Change your current directory to the debdroid root folder and try again." >&2
        exit 1
    fi
done

# shellcheck disable=SC1091
. ./debdroid/debdroid_env.sh
# shellcheck disable=SC1091
. ./debdroid/debdroid_ui.sh
setup_colors

# Checks for a matching architecture
ARCH=$(getprop ro.product.cpu.abi)
if [ "$ARCH" != "arm64-v8a" ]; then
    error "Unsupported architecture: \"$ARCH\"" \
        "This script only works on arm64-v8a (aarch64)."
    exit 1
fi

# Check if the user has root permissions 
if [ "$(id -u)" -ne 0 ]; then
    error "Missing required super-user permissions."
    exit 1
fi

section "Installing DebDroid v$DEBDROID_VER"

# Creates the skeleton under /data/local
step "Populating $DEBDROID_HOME"
mkdir -p "$DEBDROID_HOME" \
    "$DEBDROID_HOME"/bin "$DEBDROID_HOME"/mnt "$DEBDROID_HOME"/lib

# Installs project binaries
cp -r ./bin/ "$DEBDROID_HOME"
for bin in "$DEBDROID_HOME"/bin/*; do
    [ -f "$bin" ] || continue # Ensures that the binary exists
    chmod 755 "$bin"
done
chown -R root:root "$DEBDROID_HOME"/bin

# Enables colors on a fresh install, now that busybox is installed
setup_colors

# Installs project libraries
cp -r ./lib/ "$DEBDROID_HOME"
for lib in "$DEBDROID_HOME"/lib/*; do
    [ -f "$lib" ] || continue # Ensures that the library exists
    chmod 644 "$lib"
done
chown -R root:root "$DEBDROID_HOME"/lib

# Makes the directories traversable, so non-root chroot users can load preloaded libraries
# NOTE: Android root shells often run with umask 077, which makes mkdir and cp create 700 folders
chmod 755 "$DEBDROID_HOME" "$DEBDROID_HOME"/bin "$DEBDROID_HOME"/lib "$DEBDROID_HOME"/mnt

# Creates the skeleton under /sdcard
step "Populating $DEBDROID_SDHOME"
mkdir -p "$DEBDROID_SDHOME" \
    "$DEBDROID_SDHOME"/img "$DEBDROID_SDHOME"/command

cp -r ./command "$DEBDROID_SDHOME"
cp -r ./debdroid/* "$DEBDROID_SDHOME"

if [ -e "$DEBDROID_SDHOME"/img/debian.img ]; then
    step "Keeping the existing debian image: \"$DEBDROID_SDHOME/img/debian.img\""
else
    step "Creating the debian image"
    cat ./img/debian.img.part-* > "$DEBDROID_SDHOME"/img/debian.img

    step "Enter a size for the debian image (e.g. 5G, 512M), or press Enter to keep the default:"
    read -r size
    [ -n "$size" ] && sh "$DEBDROID_SDHOME"/debdroid_resize.sh "$size"
fi

section "Successfully installed DebDroid v$DEBDROID_VER!"
