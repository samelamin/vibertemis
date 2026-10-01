# Quest preview 10 (0.1.0.10) - DRAFT release notes

Status: **DRAFT, test candidate only.** These notes describe what has been
implemented in the working tree. Validation, CI, hosted downloads and the
announcement channel are still pending. Facts get updated by the writer after
root review; do not treat anything below as a finished test report.

## What changed in this build

- **One Update.** The Quest app now performs a single update action instead of
  a separate check-then-install pair.
- **Automatic checks.** Update availability is checked on its own; the user is
  not asked to trigger the check.
- **New Quest setup/connection flow.** The headset flow is split into explicit
  inline phases: "Set up PC" and "Connect", each showing its own progress and
  result state rather than a single opaque step.
- **Shorter Windows approve/reject path.** The Windows side of the handshake is
  reduced to a short Approve / Reject decision.
- **Advanced section.** Less common connection and VR options moved behind a
  clearly labelled Advanced section.
- **Native C++ packaging fix.** The native C++ payload packaging step is fixed;
  the built package is assembled correctly instead of producing a broken
  native payload layout.

Everything that already worked in preview 9 is preserved.

## Known limits

- Test candidate. Hardware has **not** been tested on real Quest hardware.
- Native VR over a public WAN is not supported.
- Vibeshine is unchanged in this build.

## Upgrading from preview 9

The updater shipped in preview 9 is broken, so the first step is a **one-time
manual APK install** of the preview 10 APK. The in-app updater takes over from
there.

After the manual APK install, the Windows side must match:

1. Install the matching Windows version 0.1.0.10 (Host Manager).
2. If VR is not already set up on Windows, run "Set up VR" in the Windows app.
3. On the Quest, run "Set up PC" and compare the displayed values, then
   Approve.
4. Run "Connect" on the Quest.
5. Allow a restart only if SteamVR is currently running.

## Pending before this becomes a release

- [ ] Combined validation (hardware + host) completed
- [ ] CI green on the release commit
- [ ] Download URLs uploaded and verified reachable
- [ ] Telegram announcement posted
