# Vibertemis XR Preview 9 — release notes

Preview `0.1.0.9`, sequence 9.

## What's new

- **Headset-initiated pairing with one Windows approval.** Quest **Setup VR** finds standalone Windows hosts on the LAN on its own; you pick the PC, compare the four-group code shown on both screens, and approve it in a non-modal panel on the Windows manager. No pairing file copy and no Moonlight pre-pairing.
- **Persistent pairing reception.** Successful Windows **Setup VR** keeps pairing reception on while the saved opt-in stays enabled and the companion is running. **Pair headset** can re-enable it later if you ever turn it off.
- **Automatic, visible update checks.** Both clients request a coalesced, throttled release-metadata check on open and on idle. Status distinguishes checking, available, downloaded, current and failed. Downloads and installs remain explicit actions. A manual **Check for updates** button is always available.
- **Retry / cancellation improvements.** Failed or cancelled operations release the busy state so **Retry** works, errors stay visible until the next user action, and closing an activity stops its download without cancelling the shared metadata repository.

## Boundaries

- **Experimental Quest 3 / Windows 11 / RTX 4090 build.** This is a Quest 3 preview using a custom ALVR 20.14.1 protocol. The native codec is unchanged from the previous preview. Real-device physical headset testing is still required.
- **No public WAN transport.** The native VR transport over the public Internet is not implemented in this build. Use the LAN path or an already reachable endpoint via **Enter address**.
- **Use matching 0.1.0.9 packages.** To exercise this flow, install `vibertemis-quest-preview-0.1.0.9.apk` on Quest and `VibertemisVR-HostManager-Setup-0.1.0.9.exe` on the PC. The native protocol is unchanged from the previous preview, so mixed versions continue to speak the same wire format.
- **Vibeshine is unchanged.** Screen streaming still uses your existing Vibeshine; this preview does not fork or update it.

For the normal setup flow see `quest/README.md`; for the acceptance checklist see [preview9-test.md](preview9-test.md).
