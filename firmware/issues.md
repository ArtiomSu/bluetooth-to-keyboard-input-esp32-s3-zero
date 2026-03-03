# Issues major

1. Replay attacks (most serious) [done]

An attacker who passively captures one valid encrypted packet can replay it indefinitely — until the ESP32 reboots. Since the ESP32 has no memory of packets it's already seen, a captured "type my password" packet would work again later.

2. No public key authentication (MITM during key exchange) [done]

The ESP32's public key is served over unauthenticated, unencrypted BLE. A BLE MITM attacker could substitute their own public key, decrypt your traffic, and re-encrypt it for the real ESP32. This requires active physical proximity during the connection, not just passive sniffing.

The fix requires a pre-shared secret to authenticate the public key — e.g. HMAC-sign the public key with a secret that only you and the ESP32 know, burned into firmware. Without that anchor, you can't verify the key.

3. Single keypair per boot, not per connection [done]

The ESP32 generates one keypair on boot and reuses it for every connection during that session. If an attacker captures multiple sessions' traffic and later extracts the private key (e.g. via JTAG/SWD), they can decrypt all of them retroactively.

Fix: call mbedtls_ecp_gen_keypair again in ServerCallbacks::onConnect() and update pPubKey->setValue() with the new bytes. Each connection then gets a fresh keypair.

4. Layout/OS characteristics are unauthenticated [done]

Anyone in BLE range can write to the layout and OS characteristics to change how keystrokes are interpreted. Practically low impact, but easy to protect: encrypt those writes too (or at minimum add a simple HMAC).

5. mtu_size - 3 chunk limit not enforced in firmware [done]

The Python client has a MAX_PLAINTEXT = 452 limit, but decryptPayload only checks cipherLen >= MAX_STR (512). A malformed oversized packet from a different sender could write up to 511 bytes into a 512-byte buffer — currently safe by exactly 1 byte but fragile. The check should be > MAX_STR - 1.

# Issues minor

1. noInterrupts() doesn't protect cross-core access (correctness bug — high) [done]
ESP32 is dual-core. loop() runs on core 1; BLE callbacks run on core 0. noInterrupts() only masks interrupt delivery on the calling core — it provides no exclusion against the other core. The queue can be corrupted via a race between cores.

Fix: replace noInterrupts()/interrupts() with ESP-IDF portMUX critical sections, which actually coordinate between cores.

2. MTU size not enforced in Python — CHUNK_SIZE is dead code (reliability — medium) [done]
send_string sends the whole packet in one write without checking client.mtu_size - 3. If the negotiated MTU is less than 515 (common on some macOS/iOS BLE stacks), the write silently fails or is rejected. The MTU is printed but ignored.

Fix: check len(packet) <= client.mtu_size - 3 before writing; raise a clear error if not.

3. Sensitive crypto material left on the stack (security hygiene — medium) [done]
In decryptPayload, the AES key, shared secret, and PRK (from HKDF) are left in stack memory after the function returns. If another function overwrites the same stack region slowly, those bytes persist in RAM and could be leaked via memory-safety bugs elsewhere.

Fix: memset the sensitive buffers to zero before returning.

4. regenKeypair() silently ignores mbedTLS error codes (correctness — medium) [done]
If the RNG is not seeded yet (or ecp_gen_keypair fails for any reason), the function silently completes with the old key still loaded — including on the very first call at boot. The device would advertise a key it can't use.

Fix: check the return value; halt or log a fatal error on failure. device will just reboot now

5. os parameter shadows the os module import in main() (code clarity — low) [done]
It works today only because os.urandom is called inside encrypt_payload, which resolves os from the module globals, not main's frame. But it's a landmine — any future use of os.urandom() or os.path inside main would silently use the string "other".

Fix: rename the parameter to target_os.