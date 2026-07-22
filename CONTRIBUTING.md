# Contributing to Vibertemis

Vibertemis is maintained at
[samelamin/vibertemis](https://github.com/samelamin/vibertemis). It is a
Steam Deck-focused fork of
[upstream Artemis](https://github.com/wjbeckett/artemis); keep upstream credit
and compatibility identifiers intact when changing fork-owned behavior.

## Before opening a change

- Search the [Vibertemis issues](https://github.com/samelamin/vibertemis/issues)
  for existing reports.
- Base work on the current development branch named in the repository or issue,
  rather than assuming a `main`/`develop` Git Flow.
- Keep changes focused and avoid renaming technical Artemis identifiers unless
  the change includes an explicit migration and compatibility review.

Clone the fork with submodules:

```bash
git clone https://github.com/samelamin/vibertemis.git
cd vibertemis
git submodule update --init --recursive
```

Create a topic branch and push it to your fork:

```bash
git switch -c feature/short-description
git add path/to/changed-file
git commit -m "feat: describe the change"
git push -u origin feature/short-description
```

Open the pull request against
[samelamin/vibertemis](https://github.com/samelamin/vibertemis), describe the
user-visible effect, list the verification performed, and link related issues.
Include screenshots for UI changes and redact logs before attaching them.

## Code and documentation standards

- Follow the existing Qt/C++ and QML conventions in the surrounding code.
- Add focused regression coverage for behavior changes and run the relevant
  packaging or platform tests.
- Update current user documentation when UI names, installation, packaging, or
  compatibility behavior changes.
- Use Vibertemis for the current public product and UI. Use “upstream Artemis”
  when discussing the parent project, and label retained `Artemis` executable,
  settings, source, URL, MSI, registry, Flatpak, and asset names as compatibility
  identifiers.
- Do not claim physical Steam Deck, HDR, codec, architecture, or real-host
  behavior from software-only CI.

## Testing

At minimum, run the smallest test that exercises the change and confirm it fails
before a bug fix or new behavior, then passes afterward. Before submitting, run
the broader applicable suite and inspect the exact CI jobs for the commit.

Platform guidance:

- Steam Deck installation and hardware evidence: [docs/STEAM_DECK.md](docs/STEAM_DECK.md)
- macOS build and bundle verification: [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#macos-development-builds)
- Windows ARM64 packaging: [docs/WINDOWS_ARM64.md](docs/WINDOWS_ARM64.md)
- Current workflow artifact status: [docs/BUILD_SYSTEM.md](docs/BUILD_SYSTEM.md)

## Reporting bugs

Open reports in the
[Vibertemis issue tracker](https://github.com/samelamin/vibertemis/issues).
Include the exact commit or artifact version, operating system and hardware,
host software/version, requested stream settings, repeatable steps, and relevant
redacted client and host logs. For behavior inherited from the parent project,
identify it as upstream Artemis behavior rather than silently routing users to
the upstream tracker.
