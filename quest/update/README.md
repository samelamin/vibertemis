# Signed Quest preview updates

Both clients fetch the public release list for `samelamin/vibertemis` and select
only `quest-preview-v<version>` releases containing `quest-update.json` and
`quest-update.json.sig`. Desktop `/latest` is never used. Checks are explicit;
no background downloads or automatic execution.

Schema 1 contains `channel: quest-preview`, positive monotonic `sequence`,
four-component `version`, `native_protocol`, and `assets.windows` / `assets.android`.
Each asset has `filename`, exact GitHub release `url`, `bytes`, and lowercase
`sha256`. Android also has `package`, `version_code`, and `signer_sha256`.
The manifest is at most 64 KiB. The detached 384-byte signature uses RSA-3072,
SHA-256, PKCS#1 v1.5 over the exact UTF-8 file bytes, including its final newline.

`public-key.pem` is the trust anchor embedded in both applications. Keep private
signing material outside source control. After validating final asset identity,
size and APK signer, use `sign-release.py --help` to produce metadata. Upload it
alongside those exact assets. Replacing an asset requires signing new metadata.

Clients reject duplicate fields, invalid signature/channel, unexpected asset
URLs, excessive sizes, corrupt downloads and incompatible native protocols.
They verify cached bytes again before handing off to the OS installer. Android
also requires the APK package/version and signer to match the installed app and
signed metadata. Installation always goes through Android's confirmation;
unknown-source permission is requested only when the owner chooses Install.

Windows refuses updates during active VR, stops only its own companion, and
passes its PID to the installer for a bounded exit wait. User settings and
pairing survive. APK/EXE hardware validation remains separate from integrity
and installer checks; signatures do not promise streaming performance.
