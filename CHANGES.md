# Asterisk Android – v1.2.0 changes

## Callers now really hear the prompts
The server answers the caller itself (200 OK + G.711 RTP) and streams the prompt, instead of playing it on the server phone's speaker. Works for: Extension Offline, Busy, Call Rejected (603), No Answer, Timeout, Network Error, Invalid Number, Forbidden.
- **No Answer** is separate from Timeout: ring time = the Timeout field of the matching *No Answer* rule (default 120 s without a rule). The old callee is CANCELled properly.
- Rules for one condition run as a chain (e.g. Play Announcement → Send to Voicemail). Each rule runs once per call.
- Caller hang-up (CANCEL) deliberately runs no rule.

## Dial-plan actions (Action Data formats)
| Action | Action Data |
|---|---|
| Play Announcement | `promptId` or `1,2` |
| Send to Voicemail | `[greetingPromptId][;max=SECONDS]` – recording saved as WAV, callee gets a SIP message; dial `*97` to listen (7 = delete, # = exit) |
| Forward to Extension | `101` (unanswered call is re-routed, loop-protected) |
| Forward to Queue | `101,102;strategy=ringall\|linear\|random\|leastrecent\|fewestcalls\|rrmemory;timeout=60;moh=promptId` |
| Forward to IVR | `promptId\|1=ext:101\|2=queue:101,102\|3=vm:105\|4=play:7\|0=hangup` |
| Hang Up | – |
| Schedule Callback | `[promptId]` – when the called extension is available the server rings the caller, then connects them (kept 24 h, in memory) |
| Retry | uses Max Retries; waits min(Timeout,5) s |
| Custom | `reject:486 ; tone:busy ; message:text ; play:ID ; hangup` |

Queues / IVR / callback bridge the two legs through the server (audio, G.711, RFC 2833 + SIP INFO DTMF).

## Offline messages & files
- Message keep time is now a setting (Settings → Calls & Media, default 3 days, was fixed 7).
- **File sharing over SIP + MSRP** (RFC 4975/5547): MSRP sessions are relayed through the server when media is relayed (set Media mode = Relay for NAT'd clients). A file offered to an offline extension is accepted, stored (max size and keep days in Settings) and pushed with INVITE + MSRP when the extension registers.
- Database v7 (voicemails, offline_files); migration keeps existing data.
- Default rules are no longer re-inserted on every launch (duplicates are cleaned up).

# Asterisk Android – v1.1.0 changes

## Fixed
1. **Extensions dropping after restart** – registrations are now saved in the database (Room v6, migration keeps your data) and restored on start. Devices are asked to re-register every 120 s (Settings → Calls & Media), server keeps NAT open with OPTIONS pings, wake-lock no longer expires after 10 min, WifiLock added, server resumes after the service is killed/boot, saved config is loaded on every start.
2. **Video calls** – SDP is handled per media line (audio + video), RTCP is relayed (PLI/FIR), ICE candidates are stripped so clients cannot bypass the relay, relay is one NIO thread (no thread starvation).
3. **Relay or direct** – Dashboard / Settings: Relay, Direct, Auto (direct when both devices are on same network). Applies to the next call, no restart.

## Also fixed in the SIP engine
Record-Route (server always sees BYE → no leaked relays), correct CANCEL branch, responses routed via Via, TCP transport, INVITE auth for unknown addresses, nonce validation, brute-force ban, UTF-8 safe MESSAGE queue, call/ring/idle timeouts.

## GUI
Material 3 theme with dark mode, new dashboard (status hero, media-mode switch, registered devices, live calls, real CPU, relay kbps, battery-optimisation tip), online/offline on Extensions, console share button.
