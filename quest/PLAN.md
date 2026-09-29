# Quest VR implementation plan and review status

Target: Meta Quest 3; Windows 11 with RTX 4090. User authorized Codex to take
 over code fixes and VR integration after repeated MiniMax review failures.
Agy remains the second reviewer. No Claude consultation in this phase.

## Preserved baseline

Preview 0.1.0.2 uses pinned Moonlight XR 0.3 and ALVR 20.14.1. It provides
Screen gaming and a separate OpenXR PCVR activity, with headset detection,
Touch input through ALVR, microphone/launch guards, nested VR preferences,
Screen bitrate up to 200 Mbps, and synthetic depth disabled by default.
Existing package/signing identity and the single OpenXR loader must survive.
See README.md, TESTING.md, RESEARCH.md and pins/pins.txt for provenance.

## Host control phase

Files: quest/host (Go), .github/workflows/quest-host-control.yml.
- Per-user Windows companion; interactive desktop required to launch SteamVR.
- Imported certificate pin plus HMAC-authenticated HTTPS requests. All reads
  authenticated; bounded requests, replay cache, separate read/start budgets.
- Only explicit role=headset, mode=pcvr start requests launch SteamVR.
  Phone, Screen gaming, settings, discovery and status do not start VR.
- Stock ALVR configuration is read-only. Codec mismatch reports reconnect /
  host configuration required; saved preference is never called negotiated.
- Startup transaction ownership uses unique pointers. Status resolves only
  dispatched requests, preserving ID/payload receipts. Preflight cannot lose
  its gate to lease expiry; stale completion cannot unlock a new owner.
- Dispatched and uncertain launches retain a 60-second lease. Completed
  results are bounded and reserved before side effects.
- Windows pairing secrets use protected owner/System DACLs.

Validation: Codex independently passed full Linux race suite, vet, and Windows
cross-build. Targeted regressions cover observation/retry identity, expired
owner reuse, cache capacity, nonce expiry, polling budgets and Session0.
Windows 2022 runtime tests and Linux race checks passed in GitHub Actions
run 36543525481 at d2203882.

Agy Gemini 3.1 Pro (High) reviewed the plan and implementations. Earlier
ownership/history flaws were rejected and fixed by Codex. Final host review
found no P0/P1 issues and approved this phase conditional on Windows CI.
The consultation does not approve the unfinished Android/native phases.

## Android control phase (integrated local build)

Files: quest/overlay, consolidated eight-file upstream patch.
- Pair with one bounded JSON import/paste; AndroidKeyStore AES-GCM key,
  authenticated ciphertext in no-backup storage; no plaintext fallback.
- Instance-scoped pinned TLS, HMAC contract matching Go, redirects disabled.
- Async explicit PCVR connection; fresh nonces and stable retry request ID;
  lifecycle/permission checks, cancellation, actionable errors, manual route.
- Separate PCVR Standard codec and Travel override; preserve PyroWave choice.
- Requested settings are distinct from the last decoded stream. PyroWave is
  enabled with matching fingerprint-verified native libraries; runtime probes
  select a supported codec.
- Keep existing 99 Android tests; add network/pairing/lifecycle regressions
  and large-font / headset-panel screenshots.

Agy reviewed this plan and the corrected implementation (Gemini 3.1 Pro High,
2026-09-29); approved with no P0/P1 findings. Codex passed all 111 existing
and new tests, built the APK, and inspected headset/large-text screenshots.
One additional screenshot regression passes. Real AndroidKeyStore hardware
validation remains required. The optional length-check optimization was
applied; stable local radio IDs are intentional for view state restoration.
Accepted: pinned leaf identity, request idempotency,
truthful connection labels and lifecycle gates. Clarified storage uses keys
in AndroidKeyStore and ciphertext outside backup; no global trust bypass.

## Native codec phase (integrated; Windows CI passed)

Selective port onto pinned ALVR20.14.1, retaining AV1/HEVC/H264 and Quest
OpenXR/Touch/haptics. Build matching custom Windows driver and Android client
with an explicit prerelease protocol identity. Do not import Galaxy-specific
presets, eye-tracking assumptions, hidden standard codecs or UDP experiments.

Native handshake carries requested codec, standard fallback and bounded
restart consent. Actual decoder/encoder capabilities select the codec; the
existing decoder config identifies what is really in use. ALVR owns its
configuration; the Go companion must not race its session.json writes.

PyroWave uses actual GPU encoder/decoder integration, bounded frame lifetime,
format/size validation and GPU waits. Probe Vulkan features, including
extension-based alternatives to Vulkan1.3 where supported. Missing features
or initialization failures must preserve standard codecs and report why.
Quest3 supports fixed foveation; do not offer nonexistent eye tracking.

The integrated Android build passes 115 tests and includes the custom ALVR,
PyroWave and conversion bridge libraries, with matching source fingerprints
and hashes. APK checks confirm arm64 only, one OpenXR loader, preserved JNI
codec callback and the existing signing identity. The local preview 3 APK
SHA256 is cd47c0b7b3a799ecb88f1bde99fcc2f927b109e3a0a401760dd079106fe5c1e0.
Two pure Rust tests cover native request selection and decoder config bounds.

Agy Gemini 3.1 Pro (High) approved the revised native C++/Rust paths and then
the Go/native handshake integration on 2026-09-29. A stale-backup flaw in a
legacy, non-HTTP adapter helper was fixed with a regression test. Saved or
requested codecs are never reported as negotiated by the companion. The
headset records the last codec only after a real decoded frame.

Windows native CI compiled at 422303f4, then found a DLL packaging-name
mismatch. Corrected in ad800c0b; CI run 36550716420 passed and produced the Windows host.
Updated companion Windows runtime and Linux race CI passed run 36550715598.
Agy approved protected pairing export and DLL packaging fixes. In-app setup
now links to the custom host and matching guide; Android tests pass again.
Actual Quest/4090 interoperability and latency remain untested. Home caps
adaptive bitrate at up to 200 Mbps, Travel uses Standard and 30 Mbps by
default (45 maximum). Restart consent is explicit and expires after 2 minutes.

## Delivery gate

No P0/P1 or incomplete feature may be presented as working. Both Windows builds and the Android build now pass. Verify matching Windows/APK artifacts,
checksums, source archive and installation instructions before notifying the
owner. Real Quest/4090 latency, visual quality and tracking need owner testing.
Telegram notification requires the owner's configured destination; no token
should be pasted into chat or logs.


Final UX review: Agy Gemini 3.1 Pro (High) approved the corrected PC/headset
step ordering and accurate headset codec/bitrate descriptions. Codex retained
ALVR client trust after clarifying that companion pairing covers HTTPS only.
Cache-safe native checkout and corrected release/guide links were approved.
Final Android rebuild: 115 tests, no failures/errors/skips.

Delivery uses a separate experimental Quest tag/branch. Main push would
trigger an unrelated automatic desktop production release, so this test build
is not merged into main before owner hardware results. The desktop update
channel and existing Steam Deck connection fixes remain unchanged.


Final package verification: downloaded Windows CI artifact; confirmed the
PyroWave DLL is beside the driver, all 16 requested API exports exist, and
runtime dependencies match the documented Visual C++ x64 prerequisite.
Standard encoders do not statically depend on the optional PyroWave DLL.
Matching Windows sources are unchanged from ad800c0b. Source-only UX/docs
commits do not change native libraries or companion binaries.
