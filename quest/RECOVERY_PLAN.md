# Preview5 automatic host recovery

Owner request: after one-time Windows/Quest setup, sign in to the PC and connect
from the headset without reopening host settings.

## Implementation and boundaries

- `Core/Recovery/HostRecoveryController.cs`: serialized desired-running state,
  monotonic time, saved-interface selection, 2-second network polling, bounded
  crash/start backoff (2/4/8/16/30 then 60 seconds). Backoff resets only after
  60 seconds of observed stable process operation. No blocking startup sleep.
- MainForm routes manual Start/Stop, sign-in restoration, update and Exit through
  that controller. A network-change event accelerates detection. Only the exact
  owned companion process is stopped for rebinding. No SteamVR process changes.
- Integrity failure requires explicit retry after repair. Other failures recover
  automatically. The status says "service running", not "VR stream verified".
- Periodic process inspection drives recovery, not asynchronous Exited callbacks;
  old notifications cannot disrupt replacement processes.
- Suspend and Stop share the same lock as reconciliation. Installer/Exit suspend
  before stopping; failed handoff resumes prior desired intent. Explicit Stop
  clears that intent, including persisted next-launch restoration.
- One "Keep host ready after Windows sign-in" setting registers the installed
  manager in HKCU Run and enables restoration. Fresh installs recommend it,
  with registration on explicit Start. Existing false preferences survive the
  upgrade. Registry failures restore the prior checkbox/settings and are shown.
- Missing NIC/DHCP wait never overwrites the saved selection. Multiple addresses
  prefer the existing valid binding. Identity and pairing directory remain fixed.

Non-goals: before-login Windows service, waking a powered-off PC, arbitrary
Internet NAT traversal, automatic headset pairing, changing native streaming
code, or silently removing the existing Quest SteamVR-restart confirmation.
Recovery is for the owned companion while the manager is running; it does not
restart a crashed Windows manager executable itself.

## Consultation and review

Agy Gemini 3.1 Pro (High) consulted before implementation. Accepted: updater
must suspend before Stop; saved NIC must not silently fall back; old startup
preferences must survive. Existing Go authenticated start handler already
checks live SteamVR and retains pairing state, so companion recovery doesn't
need to spawn or attach another SteamVR process. Rejected: interpreting rapid
crashes as cryptographic integrity failures; genuine hash failure is separate.

MiniMax M3 via opencode produced the initial controller/settings draft in this
isolated worktree. Codex review found lost serialization, callback races,
blocking startup sleep and premature backoff reset. Agy confirmed those findings.
Codex used the owner's daily direct-writing grant to replace that draft with a
smaller serialized polling controller and integrate it into the shipped UI.

Windows smoke review: use bounded WM_GETTEXT for cross-process controls, ensure
cleanup even if a test process exits while being stopped. The companion binds
one explicit IPv4 and saves pairing before listening; dual-stack and unfinished
identity-file concerns do not apply. Exact owned listener address is asserted.

## Validation

- Core tests include delayed NIC, no unrelated fallback, unusable/multiple IPv4,
  exponential crash backoff/stable reset, false-start failures, explicit Stop,
  update suspension/resume, DHCP rebind, stale exit, denied/timed-out Stop,
  integrity block and concurrent Start/Stop; 122 tests pass locally.
- Go race tests and vet pass; Android 123 tests pass, same APK signer and native
  library hashes. Version metadata advanced to preview5; native protocol unchanged.
- Windows installer CI tests the actual installed manager in tray mode, startup
  registry entry, killed-child recovery, Stop suppression, new manager launch
  with saved state, unchanged pairing, and absence of SteamVR startup. Existing
  install/reinstall/tamper/busy refusal/uninstall retention checks remain.
- Final Agy Gemini 3.1 Pro (High) implementation review: APPROVE, no P0/P1.
  Remaining observations: up to one-second tray wake delay and deliberate
  reselection if a physical NIC is replaced. Windows CI still gates publication.

## Owner end-to-end test (Quest 3 / Windows 11 / RTX 4090)

1. Install/update both preview5 packages. Keep Vibeshine for flat streaming.
2. Complete network access, matching ALVR driver and headset pairing once. Enable
   **Keep host ready after Windows sign-in**; start the companion if stopped.
3. Reboot the PC and sign in. Don't open the host manager. Give Wi-Fi time to join.
4. On Quest tap **Connect**, accept the existing restart confirmation, and verify
   SteamVR sees the headset and both controllers. Start a VR game.
5. Disconnect/reconnect from Quest without touching host settings. Verify tracked
   VR still works; then choose Flat screen and check that path too.
6. Quit the VR game. Temporarily disconnect/reconnect PC Wi-Fi, then reconnect
   Quest. No re-pairing should be needed on the same LAN.
7. Optional: terminate only `vibertemis-host-companion.exe` in Task Manager. It
   should recover within its displayed retry delay. Don't kill SteamVR.
8. In the manager choose **Stop companion**. It must stay stopped until Start.
   Start again when done. Confirm phone flat streaming doesn't launch SteamVR.

These are owner hardware tests, not claims of lab-verified latency or flawless
streaming. CI simulates sign-in by executing the installed login command; it
cannot boot the owner's PC or exercise physical Quest controllers.
