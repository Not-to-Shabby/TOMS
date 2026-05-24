# TOMS — Task Tracker

## Phase 1a: Shared Library
- [x] Create `d:\TOMS\shared_lib\` with `protocol`, `crypto`, `espnow_comm` modules
- [x] Implement packet format (toms_packet_t, CRC-16-CCITT)
- [x] Implement AES-256-CBC encrypt/decrypt via mbedtls
- [x] Implement ESP-NOW init, peer management, send/recv with callbacks

## Phase 1b: Master Firmware Core
- [x] Update `platformio.ini` (partitions, sdkconfig defaults, component deps)
- [x] Create `partitions.csv`
- [x] Create `sdkconfig.defaults`
- [x] Create `main/CMakeLists.txt` and `main/idf_component.yml`
- [x] Create `main/app_main.c` (entry point, task orchestrator)
- [x] Create `main/usb_cdc/` (TinyUSB CDC-ACM device)
- [x] Create `main/storage/` (SPIFFS + NVS)
- [x] Create `main/power/` (battery ADC)

## Phase 1c: Master UART Pogo-Pin
- [x] Create `main/uart_sync/` (state machine, packet I/O)

## Phase 2: Slave Firmware
- [x] Create `d:\TOMS\esp32_slave\` PlatformIO project
- [x] Create display driver (ST7735S, 128x160, SPI)
- [x] Create QR code generation + rendering
- [x] Create button input (GPIO interrupt + debounce)
- [x] Create UI screens (welcome, fare, QR, error, sleep)
- [x] Create deep sleep management (ext0 wake on button, timer)

## Phase 3: Flutter App
- [ ] Create `d:\TOMS\toms_app\` Flutter project
- [ ] USB serial service + protocol codec
- [ ] Local database (Isar/SQLite)
- [ ] Dashboard, occupancy, fare, receipts screens
- [ ] Offline sync queue + Supabase sync

## Phase 4: Supabase Backend
- [ ] Create Supabase project + schema SQL
- [ ] Edge Functions (batch sync, revenue calc, anomaly detection)

## Phase 5: Integration
- [ ] End-to-end testing
- [ ] Documentation / walkthrough
