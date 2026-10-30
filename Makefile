# DebDroid test and check targets
#
# The scripts need no build step, so these only check and test them.
#
#   make check    Syntax and shellcheck, no device needed
#   make test     Host test suite, no root and no device needed
#   make all      Both of the above

SCRIPTS = install.sh $(wildcard debdroid/*.sh) $(wildcard command/*.sh) \
          $(wildcard tests/*.sh) $(wildcard boot/*.sh) $(wildcard tools/*.sh) \
          $(wildcard package/*.sh) $(wildcard module/*.sh) bin/debinit

.POSIX:
.PHONY: all check syntax shellcheck provenance docs version test test-device help

all: check version provenance docs test

help:
	@echo 'make check        Syntax check and shellcheck every script'
	@echo 'make provenance   Verify every bundled binary against the manifest'
	@echo 'make docs         Check documentation links and guide vocabulary'
	@echo 'make version      Check every file naming a version agrees'
	@echo 'make test         Run the host test suite'
	@echo 'make all          All of the above'
	@echo 'make test-device  Print how to run the device suite'

check: syntax shellcheck

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
shellcheck:
	@if command -v shellcheck > /dev/null 2>&1; then \
	    shellcheck -s sh -e SC1090,SC1091,SC2016,SC2153,SC2059 $(SCRIPTS) \
	        && echo 'shellcheck: ok'; \
	else \
	    echo 'shellcheck: not installed, skipping'; \
	fi

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

# The device suite has to run on the device, so this only prints the command
test-device:
	@echo 'Run the device suite on a connected device:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh"'
	@echo
	@echo 'Add --session to also start one real session and verify cleanup:'
	@echo
	@echo '  adb shell su -c "sh /sdcard/debdroid/tests/device.sh --session"'
