# Quest-only manual test: preview.14 (Moonlight XR v0.4.2 + stream resume)

## Scope

- Target: Vibertemis XR Preview **0.1.0-quest-preview.14** (versionCode 14) on Quest 3.
- Screen mode is rebased from Moonlight XR v0.3 onto **v0.4.2** (`686429ab`).
- `0003-flat-tracking.patch` and `0004-input-resume.patch` are retired: v0.4 rewrote the
  native renderer and ships its own versions of both (Meta hand aim with the runtime's
  pinch, deliberate pinch, input released whenever the session is not focused).
- New `0006-stream-resume.patch` + hub change: opening the app from the library while a
  stream is still up returns to that stream.

No Windows or PC host change. Install over preview.13 (same package and key, do not
uninstall).

## Test steps (record pass/fail, do not infer)

1. **Return to a running Big Screen stream**
   - Start a Big Screen stream to the Desktop or a game with audio.
   - Press the Meta button, go to the library (or Lightning Launcher) and open
     Vibertemis again within 60 s.
   - Expected: you land straight back in the same stream, no PC list, no reconnect.
     Audio is muted while away and comes back on return.
2. **Hold expiry**
   - As step 1, but stay away for more than 60 s, then open Vibertemis.
   - Expected: the stream has ended; the hub shows normally.
3. **Meta menu overlay only**
   - Open and close the Meta menu over the stream 3-5 times without leaving.
   - Expected: stream continues; pointer works at once; no stuck click.
4. **3D on a flat game**
   - Start a Big Screen session on a flat game. It starts in 3D (ZipDepth).
   - Toggle the 3D button on the session bar off and on; try the Comfort / Balanced /
     Strong presets on the cog's 3D tab.
   - Expected: clean switch, no long freeze; depth settles after scene cuts.
5. **Pointer tracking**
   - Controllers: point and click around the Desktop; drag a window; select text.
   - Hands: pinch-click, pinch-drag; triple-pinch to lock the hands.
   - Note anything "wonky" with the input source used (controller / hand) and whether the
     *Keep the pointer awake* setting changes it.
6. **PCVR unaffected**
   - Full VR card still connects to the VR Host Manager as in preview.13.

## Known gaps

- Hardware behaviour on Quest 3 is untested at the time of writing.
- The 60 s hold is upstream's; it only applies to the immersive (Big Screen) path.
