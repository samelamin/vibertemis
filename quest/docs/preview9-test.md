# Vibertemis XR Preview 9 — acceptance checklist

Preview `0.1.0.9`, sequence 9.

Quest 3 / Windows 11 / RTX 4090 acceptance checklist. Physical headset testing is still required.

Numbered real-device checks. Observe and report; this checklist does not assert or apply fixes.

## Install both packages

1. Install `VibertemisVR-HostManager-Setup-0.1.0.9.exe` in your normal Windows account. Install `vibertemis-quest-preview-0.1.0.9.apk` on Quest 3.
2. Open the Windows manager and choose **Setup VR**. Approve any Windows setup / network prompts and let it finish.
3. Enable **Keep host ready after Windows sign-in**. Sign in to Windows and leave the manager in the tray.

## Pair without Moonlight

4. On Quest, open the hub and choose **Setup VR**. Do **not** pair Moonlight and do **not** import a pairing file. Confirm the standalone Windows host appears.
5. Select the PC. Confirm the same four-group code appears on both screens.

## Approval in flight

6. With the comparison code showing on both screens, take the headset off or pause it for ~10 seconds before approving.
7. Approve on the Windows manager's non-modal panel while the headset is still off.
8. Put the headset back on. Confirm Quest saved the pairing and the Windows request closed, leaving receiving controls available.

## Connect

9. Choose **Connect** from the hub. Grant microphone permission.
10. Confirm the existing SteamVR restart warning still appears and can be accepted.
11. Confirm controllers track, audio plays, and the PCVR view renders. (Quest 3 / Windows 11 / RTX 4090 target.)

## Desktop restore

12. End the stream from the Quest side. Observe whether the physical PC desktop returns on its own. **Do not change any Vibeshine setting**; this preview does not promise a particular restore behavior. Report what you observe.

## Saved setup across restart

13. Reboot Windows. After sign-in, confirm the manager comes back and pairing is preserved. Choose **Connect** again and confirm the saved PC reconnects without re-pairing.

## Updates

14. Open the hub on Quest and open the Windows manager. Confirm both run an automatic metadata check on open and show a clear status (checking, available, downloaded, current, or failed).
15. Use the manual check button on both sides while VR is closed. Confirm a fresh check runs without downloading or installing automatically.

## Failure and retry

16. Decline the microphone permission on a **Connect** attempt. Confirm the retry affordance works once you grant the permission.
17. If an update is available and you decline the sideload install permission on Quest, confirm the permission message remains visible. Choose **Install update** again to grant permission and continue.

## Flat streaming

18. Use **Flat screen** on Quest with your existing Vibeshine. Confirm screen streaming still works and this preview did not change Vibeshine.
