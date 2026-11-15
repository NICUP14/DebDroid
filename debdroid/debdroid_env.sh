#!/system/bin/sh
#
# Paths the scripts share, and the user's to change. Once a setting here is
# changed, the installer keeps this file across updates and saves the new
# default beside it as debdroid_env.sh.new; an unchanged file is updated.
#
# NOTE: DEBDROID_VER deliberately does not live here. Because this file is
#       preserved, the version stayed at whatever was first installed. It is in
#       debdroid_lib.sh, which is always replaced.

export DEBDROID_SDHOME=/sdcard/debdroid
export DEBDROID_IMG="$DEBDROID_SDHOME"/img/debian.img
export DEBDROID_CMD="$DEBDROID_SDHOME"/command

export DEBDROID_HOME=/data/local/debdroid
export DEBDROID_BIN="$DEBDROID_HOME/bin"
export DEBDROID_LIB="$DEBDROID_HOME/lib"
export DEBDROID_ENV="$DEBDROID_HOME/mnt"
# The following three must match the paths derived in debdroid_mgr.sh
export DEBDROID_LOCK="$DEBDROID_ENV.lock"
export DEBDROID_PID="$DEBDROID_ENV.pid"
export DEBDROID_LOG="$DEBDROID_ENV.log"

export DEBDROID_SWAP="$DEBDROID_HOME"/swapfile
export DEBDROID_WAKELOCK=debdroid
export BUSYBOX="$DEBDROID_HOME"/bin/busybox
