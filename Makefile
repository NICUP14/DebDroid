# DebDroid test and check targets
#
# The scripts need no build step, so these only check and test them.
#
#   make check    Syntax and shellcheck, no device needed
#   make test     Host test suite, no root and no device needed
#   make app      The app's gradle build and its unit tests
#   make all      All of the above

SCRIPTS = install.sh $(wildcard debdroid/*.sh) $(wildcard command/*.sh) \
          $(wildcard tests/*.sh) $(wildcard boot/*.sh) $(wildcard tools/*.sh) \
          $(wildcard package/*.sh) $(wildcard module/*.sh) bin/debinit

.POSIX:
.PHONY: all check syntax shellcheck functions provenance docs version test test-device app help

# The gradle used for the app, which commits no wrapper
GRADLE = gradle

all: check version provenance docs test app

help:
	@echo 'make check        Syntax check and shellcheck every script'
	@echo 'make provenance   Verify every bundled binary against the manifest'
	@echo 'make docs         Check documentation links and guide vocabulary'
	@echo 'make version      Check every file naming a version agrees'
	@echo 'make test         Run the host test suite'
	@echo 'make app          Build the app and run its unit tests'
	@echo 'make all          All of the above'
	@echo 'make test-device  Print how to run the device suite'

check: syntax shellcheck functions

# Both shells are checked, as the product targets whatever /system/bin/sh is
syntax:
	@for file in $(SCRIPTS); do \
	    sh -n "$$file" || exit 1; \
	    bash --posix -n "$$file" || exit 1; \
	done
	@echo 'syntax: ok'

# Excluded checks, each a deliberate pattern rather than an oversight:
#   SC1090/SC1091  sourced files shellcheck cannot follow at parse time
#   SC2016         single-quoted awk programs, which must not expand
#   SC2153         distinct variables it mistakes for typos of each other
#   SC2059         the test helper forwarding a printf format it was handed
#   SC3013         "test -ef", which POSIX does not define but every shell this
#                  runs under implements: android's /system/bin/sh (mksh), the
#                  bundled busybox ash and the root manager's busybox were each
#                  checked on a device. It compares two /proc/PID/ns/mnt links
#                  to decide whether a process is in the environment's mount
#                  namespace, which is the PID-namespace workaround from
#                  section 1 of AGENTS.md. The portable form would be a
#                  readlink per process, and that loop walks all of /proc
#                  repeatedly while stopping an environment, so it would add
#                  hundreds of forks on the slowest devices to satisfy a shell
#                  none of them run.
shellcheck:
	@if command -v shellcheck > /dev/null 2>&1; then \
	    shellcheck -s sh -e SC1090,SC1091,SC2016,SC2153,SC2059,SC3013 $(SCRIPTS) \
	        && echo 'shellcheck: ok'; \
	else \
	    echo 'shellcheck: not installed, skipping'; \
	fi

# Fails on a call to a function the script cannot see, which a shell would
# otherwise report only at runtime, and not at all behind "2> /dev/null"
functions:
	@sh tools/check-functions.sh

# Fails on a changed checksum or a binary nobody wrote down
provenance:
	@sh tools/verify-provenance.sh

# Fails on a broken internal link, a missing redirect stub, or implementation
# vocabulary creeping into the guide tier
docs:
	@sh tools/check-docs.sh

# Fails when module.prop, TODO.md or the changelog drift from DEBDROID_VER
version:
	@sh tools/check-version.sh

test:
	@sh tests/run.sh

# Kept out of "check", which stays shell only and fast, and run from "all"
# NOTE: No gradle wrapper is committed. Its jar is a binary, and AGENTS.md
#       forbids a binary without a provenance entry, so CI installs gradle
#       itself and a contributor uses their own: "make app GRADLE=/path/gradle".
app:
	@cd app && $(GRADLE) --console=plain assembleDebug testDebugUnitTest lintDebug

# The device suite has to run on the device, so this only prints the command
test-device:
	@echo 'Run the device suite on a connected device:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh"'
	@echo
	@echo 'Add --session to also start one real session and verify cleanup:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh --session"'
