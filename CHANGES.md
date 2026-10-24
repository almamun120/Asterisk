# Asterisk Android – v1.1.0 changes

## Fixed
1. **Extensions dropping after restart** – registrations are now saved in the database (Room v6, migration keeps your data) and restored on start. Devices are asked to re-register every 120 s (Settings → Calls & Media), server keeps NAT open with OPTIONS pings, wake-lock no longer expires after 10 min, WifiLock added, server resumes after the service is killed/boot, saved config is loaded on every start.
2. **Video calls** – SDP is handled per media line (audio + video), RTCP is relayed (PLI/FIR), ICE candidates are stripped so clients cannot bypass the relay, relay is one NIO thread (no thread starvation).
3. **Relay or direct** – Dashboard / Settings: Relay, Direct, Auto (direct when both devices are on same network). Applies to the next call, no restart.

## Also fixed in the SIP engine
Record-Route (server always sees BYE → no leaked relays), correct CANCEL branch, responses routed via Via, TCP transport, INVITE auth for unknown addresses, nonce validation, brute-force ban, UTF-8 safe MESSAGE queue, call/ring/idle timeouts.

## GUI
Material 3 theme with dark mode, new dashboard (status hero, media-mode switch, registered devices, live calls, real CPU, relay kbps, battery-optimisation tip), online/offline on Extensions, console share button.
