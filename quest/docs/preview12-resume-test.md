# Quest-only manual test: preview.12 pointer/input resume

## Scope

- Target: Vibertemis XR Preview **0.1.0-quest-preview.12** (versionCode 12) on Quest.
- Change under test: `quest/patches/0004-input-resume.patch` (pointer resume on focus return
  and session start, stale input cleared, release required before a fresh click **only if** a
  pinch/trigger was still held at resume).
- Build/identity change under test: `quest/patches/0001-vibertemis-quest-preview.patch`
  versionName/versionCode bump only.

**No Windows change and no rebuild of the PC host is required.** The flat-screen Desktop
test runs against the **existing Vibeshine host** already installed on the PC; nothing in
this change touches the host.

## Install

Install preview.12 over the existing preview.11 build. Same APK package id and same
signing key, so this is an in-place upgrade: **do not uninstall first**. Uninstalling
clears app data and the saved pairing. After the upgrade, confirm the headset still shows
the host as paired and streams without re-entering the PIN.

## What the fix is supposed to do

- On a new session (app launch / stream start) and on a focus return (headset worn again,
  or the system menu dismissed), the pointer is **awake immediately** where it was, not
  gated behind the deliberate-movement delay. "Immediately" means as soon as the app has
  focus **and** a valid hand/controller ray exists; it is not a claim about a literal first
  rendered frame, because resume also emits a deliberate release frame and the runtime must
  report a valid pose before a ray can exist.
- Stale input from while input was unattended is **cleared**: a deliberate release frame is
  emitted on resume, and the runtime's leftover action state is not delivered as a press.
- That release frame means a click cannot carry over from before focus was lost. The
  `inputArmed` gate blocks a fresh click until a release is seen **only when a pinch or
  trigger is actually still held at the moment of resume**. If the hand is already open, the
  gate is open: the next ordinary pinch is a normal click, and no extra pinch/release cycle
  must be inserted.

## Test steps

Each step is a manual pass/fail on a Quest. Record pass/fail; do not infer results.

1. **Initial state, Desktop (flat screen)**
   - Cold start the app, pair if needed, start the stream, and let it settle on the host
     Desktop. The host is the **existing Vibeshine install on the PC**, used as-is.
   - The pointer must come up without needing to wiggle the controller first, as soon as the
     app has focus and a valid hand/controller ray exists. Do **not** fail this step because
     the pointer is not painted on the literal first frame: one deliberate release frame and
     runtime pose validity are expected before the ray appears.

2. **Quest menu open/close, repeated**
   - Open the Quest system/app menu with the headset on, close it, repeat 3-5 times.
   - Each return to focus: pointer present and usable at once, no stuck button held at the
     Desktop, no click landing on its own.

3. **Disconnect, then resume to Desktop**
   - End the session (or lose the host connection) and start a new session to the Desktop.
   - Pointer awake immediately on the new session; no button stuck from the previous session;
     no click carried across the disconnect.

4. **Open the menu while a pinch is held**
   - While holding a pinch, open the Quest menu, **keep holding across the resume**, then
     close it and return to focus.
   - After focus returns the held pinch must read as **released** at the host. The host must
     not be left with a button held down.
   - The pinch was still held at the moment of resume, so the `inputArmed` gate is closed:
     continuing to hold through the resume must register **no click**.
   - Release, then pinch again: that pinch must produce **exactly one** normal click, acting
     on the host normally.
   - Control case, hand already open at resume: the gate is open because nothing was held.
     Do **not** insert a mandatory extra pinch/release cycle. The next ordinary pinch must
     click immediately and normally.

## Known-pending item (not part of this fix)

The **PS5 visible mouse-mode indicator is still pending** and is explicitly **not** part of
this fix. Its absence here is expected, not a regression.

## Pre-flight validation commands

These are build-side checks, run on the PC with a Python 3 interpreter and the OpenXR SDK
available. `--headers` must point at the OpenXR **include** directory, i.e. the one that
contains `openxr/openxr.h` (commonly `<OpenXR-SDK>/include`).

```sh
# from the Vibertemis repository root after applying the Android overlays
python3 quest/native/tests/test_input_resume.py --headers /path/to/OpenXR-SDK/include
python3 quest/native/tests/test_flat_tracking.py --headers /path/to/OpenXR-SDK/include
```

Adjust the script path if the scripts are checked out elsewhere. Run these before the
manual passes in **Test steps**; a green run here is a precondition, not a substitute for
the hardware record.

## What this test does not establish

- This build **is a release**: a **manual GitHub test release**. It is deliberately
  **excluded from the auto updater feed**, so the updater will not offer it to normal users.
  Exercising it here does not sign off anything in the updater feed.
- No claim is made here about full-PC VR behaviour or about WAN streaming. Those were not
  exercised by this test and remain unverified.
- **Hardware acceptance is explicitly still pending.** No hardware pass is being claimed by
  this document; it is the procedure for the owner to run and record on real Quest hardware.
