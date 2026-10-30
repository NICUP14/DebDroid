#!/system/bin/sh
# debdroid.service.sh: Starts the DebDroid environment at boot
#
# Install it as a Magisk, KernelSU or APatch service script:
#
#   cp boot/debdroid.service.sh /data/adb/service.d/debdroid.sh
#   chmod 755 /data/adb/service.d/debdroid.sh
#
# Those run late in boot, after /data is decrypted and mounted, which is what
# the environment needs. An init.d script works the same way.
#
# NOTE: A boot script that fails can leave a device unbootable, so this one
#       guards itself: it writes a sentinel before touching anything and
#       removes it once the environment is up. Finding the sentinel still there
#       on the next boot means the previous attempt failed, so it stands down
#       and leaves the device alone rather than failing the same way forever.

DEBDROID_SDHOME=/sdcard/debdroid
DEBDROID_HOME=/data/local/debdroid
BOOT_SENTINEL="$DEBDROID_HOME"/boot.incomplete
BOOT_LOG="$DEBDROID_HOME"/boot.log

# Records a timestamped line in the boot log
log()
{
    echo "$(date 2> /dev/null): $1" >> "$BOOT_LOG"
}

# Waits for the scripts to become readable, as /sdcard mounts late in boot
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 \
         21 22 23 24 25 26 27 28 29 30; do
    [ -f "$DEBDROID_SDHOME"/debdroid.sh ] && break
    sleep 5
done

if [ ! -f "$DEBDROID_SDHOME"/debdroid.sh ]; then
    log "Giving up: $DEBDROID_SDHOME/debdroid.sh never appeared."
    exit 0
fi

# Stands down when the previous boot attempt did not finish
if [ -e "$BOOT_SENTINEL" ]; then
    log "Refusing to start: the previous boot attempt did not complete."
    log "Delete \"$BOOT_SENTINEL\" to re-enable starting at boot."
    exit 0
fi

mkdir -p "$DEBDROID_HOME"
echo "incomplete" > "$BOOT_SENTINEL"

log "Starting the debdroid environment."
sh "$DEBDROID_SDHOME"/debdroid.sh start >> "$BOOT_LOG" 2>&1
status=$?

if [ "$status" -eq 0 ]; then
    log "The environment started."
else
    log "The environment failed to start (exit $status)."
fi

# Clearing the sentinel marks the boot as survived, whatever the outcome was:
# the device booted far enough to get here, so the guard has done its job.
rm -f "$BOOT_SENTINEL"
exit 0
