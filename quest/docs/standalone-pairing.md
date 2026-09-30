# Standalone VR pairing (preview 8)

Vibeshine stays unchanged. Screen-gaming PCs supply addresses, never VR trust.
No host API, service registration, host certificate, or host private key is used.
This supersedes the unpublished preview 7 host-bridge draft.

## User flow

1. Install Windows VR Host Manager, run Setup VR, then choose Pair headset.
2. On Quest, select the saved PC under Setup VR.
3. Compare all four uppercase code groups on both screens. Approve on the PC
   only if every character matches. Reject mismatches.
4. The PC prompt closes automatically. Quest finishes saving the pairing.

Approval is saved across restarts. Taking the headset off to approve does not
cancel the attempt. Pairing and Windows sign-in never launch SteamVR; an explicit
VR Connect request does. Flat streaming remains independent. PC Pair headset also
offers Forget paired headsets; this revokes standalone credentials immediately.
Legacy imported files remain supported and are not revoked by that action.

## Protocol

- HTTPS control remains on the selected adapter, TCP 28540. New unauthenticated
  enrollment routes: POST `/pairing/begin`, `/pairing/poll`, `/pairing/cancel`.
- Management is a separate listener bound only to `127.0.0.1:28541`, with the
  same certificate. POST `/pairing/admin/{open,pending,decision,close,forget}`
  requires the private owner token's existing HMAC, timestamp and nonce checks.
  It rejects any device header. The LAN listener never mounts these operations.
- Enrollment is closed by default and on restart. The manager explicitly opens
  a two-minute window, with one pending request and six begin attempts maximum.
  HTTP bodies are capped at 16 KiB, socket deadlines are bounded, and public
  requests have a bounded per-IP limiter. A hostile local peer can consume the
  pending slot; close and reopen the window after rejecting an unexpected request.
- The headset generates a temporary RSA-2048 key (E=65537), never saved to disk,
  plus 32 random nonce bytes. Begin sends schema 1, SPKI DER in base64 and nonce
  in lowercase hex. The server returns a random 32-byte session ID and nonce,
  its public certificate and expiration.
- Initial TLS discovery transmits no credentials. The headset requires the
  returned certificate to equal the actual TLS peer, then pins every further
  enrollment request. Only human comparison establishes initial trust.
- Comparison code: first 64 bits of SHA-256 of the following LF-separated UTF-8
  fields, no trailing newline, rendered uppercase `XXXX-XXXX-XXXX-XXXX`:
  `VIBERTEMIS-STANDALONE-1-CODE`, `1`, companion certificate DER SHA-256,
  client SPKI SHA-256, client nonce, server nonce, session ID.
- Poll/cancel prove possession using RSA-PSS SHA-256, MGF1 SHA-256, salt 32.
  Transcript: `VIBERTEMIS-STANDALONE-1-POLL` (or `-CANCEL`), `1`, session ID,
  client nonce, server nonce, companion certificate SHA, LF-separated.
- PC approval binds the exact session and comparison code. A new 16-byte device
  ID and 32-byte token are persisted privately before publishing approval. Token
  delivery uses RSA-OAEP SHA-256/MGF1 SHA-256, label
  `VIBERTEMIS-STANDALONE-1-TOKEN\n1\n<session ID>`. Only the requesting key can
  decrypt it. Closing the PC prompt preserves only the already-approved encrypted result
  until its original expiry, so Quest can finish without leaving the PC window
  open. New requests stay blocked. Polls retry the same result until expiry. Signed cancellation
  racing approval revokes that attempt's credential.
- `standalone-devices.json` uses a separate, size-bounded store, maximum 32
  records, owner-only permissions, atomic replacement and durable writes.
  No old inherited credential is promoted into this store. Every later request
  sends X-Vq-Device and its device HMAC; the current local store checks approval
  again. Missing device header retains legacy global-token compatibility.
- Failed/cancelled enrollment preserves the headset's previously saved pairing.
  The client supports best-effort private-key destruction; managed runtimes do
  not guarantee full heap erasure.

## Review and validation

Owner authorized direct Codex implementation after MiniMax failures; Agy reviewed
plan and implementation. Agy's initial alleged missing-header authentication
blocker conflicts with the actual conditional branch and cross-process evidence:
absence of X-Vq-Device uses the owner token; only present malformed headers fail.
Agy explicitly withdrew that finding after source and runtime evidence, and
approved the authentication flow. The shared HMAC/replay validator is retained,
not duplicated.

Tests cover closed/expired/rejected windows, identity/signature binding,
failed persistence, approval/cancellation races, restart, revocation, LAN admin
isolation, wrong TLS pins and actual C# manager requests to the built Go binary.
A test-only Go fixture verifies Java PSS, comparison code and OAEP interoperability.
Windows installer/update/recovery and real Microsoft prerequisite tests remain
release gates. Physical Quest/Windows 11/RTX 4090 testing is still required.

## Network limits

Independent pairing does not add internet relaying or NAT traversal. For travel,
use the supported private/VPN route; existing GameStream router forwarding alone
is insufficient for tracked VR. Loopback port 28541 must never be forwarded.
