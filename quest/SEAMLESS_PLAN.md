# Quest connection and Windows update repair

Owner request: recognize Quest as a SteamVR headset, keep repeat connections
automatic, support travel, and repair unreliable Windows component updates.

## Connection contract

- Vibertemis identifies headtracking devices plus exact Oculus/Meta Quest models.
  Phones keep the screen-streaming path and never request SteamVR startup.
- An unpaired Quest opens VR setup instructions. Manual VR is an explicit choice;
  a GameStream/Vibeshine PIN does not silently count as companion VR pairing.
- Windows **Prepare VR** verifies the bundled payload, registers its SteamVR
  driver, creates native defaults only when missing, configures network access,
  and starts the connection service. Existing login opt-outs survive. Another
  ALVR registration requires an explicit switch; unrelated drivers survive.
- Initial defaults are generated from the pinned native `SessionConfig::default`
  in preview5 source, fingerprint
  `512e3203110ed3cbc822cf456e36e239dcd3bcb764feca62c316d910a37764f2`.
  The checked-in JSON is successfully deserialized by that actual Rust type.
  Overrides match Windows/release configuration: microphone disabled,
  auto-trust disabled, disk logging disabled, warning notification level,
  Windows capture path; adaptive throughput ceiling enabled at 200 Mbps.
- Android preserves/seeds the native ALVR identity, including its exact protocol
  ID and `app_dirs2` 2.5.5 path rules. Rust's Android `home_dir` has no passwd
  fallback: nonempty HOME enables XDG, otherwise Context.getDataDir is used.
  No environment mutation or native library replacement is needed.
- A pinned, HMAC-authenticated headset start supplies that hostname. Host derives
  the address from the TCP connection, ignores proxy headers and rejects public,
  loopback, link-local and multicast addresses. Only private/CGNAT peers qualify.
  Auth/class/version checks precede registration. New clients require host6.
- Trust is added only when both SteamVR and Dashboard are idle. Existing exact
  mappings reconnect without a write. Changed mappings while VR is running
  require closing it once and retrying. The host never kills a VR game.
- Registration preserves all other session preferences/peers, checks the current
  file snapshot and both process gates again before replacement, and uses an
  atomic same-directory replacement. External launches can still race any
  process snapshot; this is not a lock shared with SteamVR itself.

## Optional travel route

Tailscale remains an external prerequisite, not a bundled VPN or account signup.
The manager exposes that adapter explicitly; fresh setup still suggests physical
network first. Prepare verifies an active Tailscale interface and canonical
100.64/10 IPv4 after elevation. Its rules target that exact local address,
remote100.64/10 and fixed ports; control also specifies the companion executable.
LAN rules remain Private/Domain LocalSubnet. No public ALVR forwarding is added.
Existing third-party firewall rules are outside this helper's ownership.
Signed pairing retains the VPN endpoint and native peer routing follows the
authenticated connection. Owner must test Quest VPN/background behaviour and
actual remote latency; bandwidth alone does not establish stream quality.

Vibeshine continues screen streaming. Its existing **Restore as soon as the
client disconnects** setting controls physical desktop restoration after the last
client leaves, including host-detected network timeout. No source fork is needed
for that setting; the Windows manager cannot silently change the owner's running
Vibeshine configuration from this build environment.

## Review and validation

Agy Gemini 3.1 Pro (High) reviewed plans and implementation. Accepted cold-only
trust changes, exact native path parity, explicit driver-conflict consent and
narrow VPN rule scope. Generic live API mutation was dropped. VPN address parsing
and adapter identity checks address the elevated-helper concerns. Pre-existing
user firewall rules are not treated as a regression introduced by these changes.
MiniMax M3 implements the updater; Codex reviews its diff and repairs remaining
issues under the owner's daily grant. See UPDATER_PLAN.md for that contract.

Validation includes Go race tests plus real signed-request authentication/phone/
public-address rejection, Android routing/identity/path/consent tests, .NET
driver/conflict/defaults/adapter tests, native Rust schema deserialization, and
Windows installed-manager/installer tests. Hardware stream quality and controller
tracking require the owner's Quest3/Windows11/RTX4090 end-to-end test.

Final Agy review (2026-09-30): approved, no P0/P1 blockers. Initial compile
claims were adjudicated with the omitted partial class and actual Uri type;
Windows cross-build passed. Agy confirmed the repaired download-handle lifetime,
peer trust, native identity and Tailscale boundary. Codex independently checked
the installed-version probe, retained processes/events, failure reporting and
test coverage. Local results: Android128, Core161, Go race/vet passed; signed APK
version6 preserves the existing signer. Real Windows CI remains the release gate.

Agy also approved the final UX delta: visible download progress text, actionable
missing-SteamVR setup guidance and an error dialog when an attempted install
cannot reopen the app. Revalidation: Core161 and Windows cross-build passed.

Windows CI first full update test passed. A repeat exposed a fixture race in
Process.MainWindowHandle, before installer execution. The test now waits for the
exact owned main-window PID/caption before commit and quits that UI thread; it
stops the worker before parent during failure cleanup. Agy approved this bounded
test-only fix. Final CI repeats the real handoff in separate PowerShell steps.
