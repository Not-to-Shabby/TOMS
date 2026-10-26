cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(return_machine LANGUAGES C)

# Portable core shared with the host-side unit tests (firmware/lib/toms_core).
set(TOMS_CORE ${CMAKE_CURRENT_LIST_DIR}/../../lib/toms_core)

target_sources(app PRIVATE
  src/main.c
  src/reset_reason.c
  src/pn532.c
  src/buzzer.c
  src/button.c
  src/ble/svc.c
  ${TOMS_CORE}/src/crc16.c
  ${TOMS_CORE}/src/codec.c
  ${TOMS_CORE}/src/state_table.c
  ${TOMS_CORE}/src/ring.c
)

target_include_directories(app PRIVATE
  src
  ${TOMS_CORE}/include
)
