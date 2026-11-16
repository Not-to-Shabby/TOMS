#!/usr/bin/env bash
# Everything that can be checked for toms_core without hardware or Zephyr:
#   1. unit tests          (-Wall -Wextra -Werror, via run_tests.sh)
#   2. strict warnings     (-pedantic -Wconversion -Wshadow ... as errors, -O2)
#   3. gcc -fanalyzer      (interprocedural static analysis, as errors)
#   4. unit tests again    with trap-mode UBSan (undefined behaviour aborts)
#   5. fuzz of every decoder, each input in front of a guard page, plus a
#      negative control that must crash so a silent guard cannot pass
#
# Not covered: the Zephyr-side code (svc.c, main.c, display_test.c). No cppcheck or
# clang-tidy is installed and gcc has no AddressSanitizer runtime on this machine.
set -u
cd "$(dirname "$0")/.."

FAIL=0
step() { printf '\n=== %s\n' "$1"; }
bad() { echo "FAILED: $1"; FAIL=1; }

SRC="src/crc16.c src/ring.c src/state_table.c src/codec.c src/clock.c src/guard.c src/reset.c src/ota.c src/sha256.c"

step "1/5 unit tests"
bash test/run_tests.sh || bad "unit tests"

step "2/5 strict warnings (as errors)"
for f in $SRC; do
	gcc -std=c99 -pedantic -Wall -Wextra -Werror -Wshadow -Wconversion -Wsign-conversion \
		-Wstrict-prototypes -Wmissing-prototypes -Wcast-qual -Wcast-align -Wpointer-arith \
		-Wformat=2 -Wundef -Wnull-dereference -Wduplicated-cond -Wlogical-op -O2 \
		-Iinclude -c "$f" -o /dev/null || bad "warnings in $f"
done

step "3/5 gcc -fanalyzer"
for f in $SRC; do
	gcc -std=c99 -fanalyzer -Werror -O2 -Iinclude -c "$f" -o /dev/null || bad "analyzer on $f"
done

UB="-std=c99 -g -O1 -fsanitize=undefined -fsanitize-undefined-trap-on-error -fno-omit-frame-pointer -Wall -Wextra -Werror -Iinclude"

step "4/5 unit tests under trap-mode UBSan"
gcc $UB test/test_main.c $SRC -o /tmp/toms_test_ub || bad "build UBSan tests"
/tmp/toms_test_ub || bad "UBSan unit tests"
gcc $UB test/test_ota.c src/ota.c src/sha256.c -o /tmp/toms_test_ota_ub || bad "build UBSan ota tests"
/tmp/toms_test_ota_ub || bad "UBSan ota tests"

step "5/5 decoder fuzz with guard pages"
gcc $UB test/fuzz_decoders.c src/state_table.c src/codec.c src/clock.c -o /tmp/toms_fuzz || bad "build fuzz"
if /tmp/toms_fuzz guardcheck >/dev/null 2>&1; then
	bad "negative control did not crash: the guard page is not working"
else
	echo "negative control crashed as required"
fi
/tmp/toms_fuzz "${FUZZ_ITERS:-300000}" || bad "fuzz run"

echo
if [ "$FAIL" -eq 0 ]; then echo "ALL CHECKS PASSED"; else echo "SOME CHECKS FAILED"; fi
exit "$FAIL"
