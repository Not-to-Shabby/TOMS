#!/usr/bin/env bash
# Build and run the toms_core host unit tests with the first C compiler found.
set -e
cd "$(dirname "$0")"

CC_BIN=""
for c in gcc cc clang; do
	if command -v "$c" >/dev/null 2>&1; then
		CC_BIN="$c"
		break
	fi
done
if [ -z "$CC_BIN" ]; then
	echo "No C compiler found (tried gcc, cc, clang). Install one or run the" >&2
	echo "tests inside WSL/Zephyr's native_sim." >&2
	exit 2
fi

"$CC_BIN" -std=c99 -Wall -Wextra -Werror -I../include \
	test_main.c ../src/crc16.c ../src/ring.c ../src/state_table.c ../src/codec.c ../src/clock.c ../src/guard.c ../src/reset.c \
	-o test_core
./test_core

"$CC_BIN" -std=c99 -Wall -Wextra -Werror -I../include test_ota.c ../src/ota.c ../src/sha256.c -o test_ota
./test_ota
