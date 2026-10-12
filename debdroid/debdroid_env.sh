#!/system/bin/sh

export DEBDROID_VER='1.2'

export DEBDROID_SDHOME=/sdcard/debdroid
export DEBDROID_IMG="$DEBDROID_SDHOME"/img/debian.img
export DEBDROID_CMD="$DEBDROID_SDHOME"/command

export DEBDROID_HOME=/data/local/debdroid
export DEBDROID_BIN="$DEBDROID_HOME/bin"
export DEBDROID_LIB="$DEBDROID_HOME/lib"
export DEBDROID_ENV="$DEBDROID_HOME/mnt"
export DEBDROID_LOCK="$DEBDROID_ENV.lock" # Must match the lock path in debdroid_mgr.sh
export BUSYBOX="$DEBDROID_HOME"/bin/busybox