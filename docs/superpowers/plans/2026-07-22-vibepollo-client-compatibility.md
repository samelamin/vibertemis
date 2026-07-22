# Vibepollo Client Compatibility Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Send a precise, internally consistent `clientRefreshRateX100` alongside Artemis's Apollo-compatible `maxFPS` value.

**Architecture:** Extend the existing pure `RefreshRateParser` protocol helpers, prove the conversion with QTest before touching session code, then assign the computed value to Moonlight's existing `STREAM_CONFIGURATION` field. The feature remains host-brand-neutral and adds no endpoint or settings UI.

**Tech Stack:** C++11, Qt 6 Core, QTest, qmake, moonlight-common-c RTSP fields.

---

### Task 1: Define the x100 wire conversion with failing tests

**Files:**
- Modify: `tests/refreshrate/tst_refreshrate.cpp`
- Test: `tests/refreshrate/tst_refreshrate.cpp`

- [ ] **Step 1: Add the new test slots**

Declare data-driven tests for `protocolFpsToClientRefreshRateX100()`, invalid fallback behavior, and half-FPS consistency boundaries.

- [ ] **Step 2: Add the conversion table**

```cpp
QTest::newRow("23.976") << 23976 << 60 << 2398;
QTest::newRow("29.97")  << 29970 << 60 << 2997;
QTest::newRow("59.94")  << 59940 << 60 << 5994;
QTest::newRow("119.88") << 119880 << 60 << 11988;
QTest::newRow("integer") << 60 << 30 << 6000;
QTest::newRow("fallback") << 0 << 60 << 6000;
QTest::newRow("large fallback still integer") << 0 << 5000 << 500000;
QTest::newRow("no usable rate") << 0 << 0 << 0;
QTest::newRow("overflowing fallback") << 0 << std::numeric_limits<int>::max() << 0;
```

Add explicit boundary assertions:

```cpp
QCOMPARE(RefreshRateParser::protocolFpsToClientRefreshRateX100(59495, 60), 5949);
QCOMPARE(RefreshRateParser::protocolFpsToClientRefreshRateX100(59496, 60), 5949);
QCOMPARE(RefreshRateParser::protocolFpsToClientRefreshRateX100(59500, 60), 5950);
```

- [ ] **Step 3: Run the focused test and verify failure**

Run:

```bash
mkdir -p build/tests-refreshrate
cd build/tests-refreshrate
qmake6 ../../tests/refreshrate/refreshrate.pro
make -j2
./tst_refreshrate
```

Expected: compilation fails because `protocolFpsToClientRefreshRateX100()` does not exist.

- [ ] **Step 4: Commit the red test**

```bash
git add tests/refreshrate/tst_refreshrate.cpp
git commit -m "test: define Vibepollo refresh-rate wire contract"
```

### Task 2: Implement the minimal consistent conversion

**Files:**
- Modify: `app/settings/refreshrateparser.h`
- Modify: `app/settings/refreshrateparser.cpp`
- Test: `tests/refreshrate/tst_refreshrate.cpp`

- [ ] **Step 1: Add the public pure helper**

```cpp
static int protocolFpsToClientRefreshRateX100(int protocolFps,
                                               int fallbackFps = 0);
```

- [ ] **Step 2: Implement integer, milli-Hz, and overflow handling**

Treat fallback as a separate integer-only branch before applying the protocol
threshold: if `protocolFps <= 0`, return `fallbackFps * 100` after positive and
overflow validation, regardless of the fallback's magnitude. Only a positive
`protocolFps` is eligible for Artemis's milli-Hz convention. For positive
integer protocol FPS, validate the multiplication against `int` max. For
milli-Hz, calculate positive half-up nearest x100 and clamp it to the band
accepted by Vibepollo's independent whole-FPS rounding:

```cpp
const qint64 nearestX100 = (static_cast<qint64>(safeProtocolFps) + 5) / 10;
const qint64 wholeFps = (static_cast<qint64>(safeProtocolFps) + 500) / 1000;
const qint64 minimumX100 = qMax<qint64>(0, wholeFps * 100 - 50);
const qint64 maximumX100 = wholeFps * 100 + 49;
const qint64 consistentX100 = qBound(minimumX100, nearestX100, maximumX100);
```

Return zero if neither protocol nor fallback is positive or if the result cannot fit in `int`.

- [ ] **Step 3: Run the focused test**

Run: `build/tests-refreshrate/tst_refreshrate`

Expected: all refresh-rate tests pass, including the 59.496 clamp.

- [ ] **Step 4: Commit the implementation**

```bash
git add app/settings/refreshrateparser.h app/settings/refreshrateparser.cpp tests/refreshrate/tst_refreshrate.cpp
git commit -m "fix: send Vibepollo-consistent client refresh rates"
```

### Task 3: Populate the Moonlight stream field

**Files:**
- Modify: `app/streaming/session.cpp`

- [ ] **Step 1: Assign the field from the resolved FPS**

Immediately after assigning `m_StreamConfig.fps`, add:

```cpp
m_StreamConfig.clientRefreshRateX100 =
    RefreshRateParser::protocolFpsToClientRefreshRateX100(
        m_StreamConfig.fps, m_Preferences->fps);
```

Log both protocol values so the manual Vibepollo acceptance test can compare them to host logs.

- [ ] **Step 2: Build the application and test project**

Run the platform-appropriate qmake build plus `tst_refreshrate`.

Expected: application compiles against the pinned Moonlight header and all refresh tests pass.

- [ ] **Step 3: Inspect generated behavior**

Confirm the pinned `SdpGenerator.c` emits `x-nv-video[0].clientRefreshRateX100` from the assigned field and that no Vibepollo brand check, ABR call, or new setting was added.

- [ ] **Step 4: Commit the integration**

```bash
git add app/streaming/session.cpp
git commit -m "fix: populate Moonlight client refresh metadata"
```

### Task 4: Record live-host acceptance without overclaiming

**Files:**
- Modify: `docs/STEAM_DECK.md`
- Modify: `README.md` only during final fork-documentation reconciliation

- [ ] **Step 1: Add a manual Vibepollo checklist**

Record client `maxFPS`/x100 logs, host mismatch warnings, effective capture rate, integer rate, and invalid-fallback cases.

- [ ] **Step 2: Label status accurately**

Use “protocol unit-tested” until a real host stream completes. Do not describe runtime bitrate, virtual display recovery, WGC, HDR peak control, WebRTC, or server UI as client features.

- [ ] **Step 3: Run final focused checks**

Run QTest, `git diff --check`, and source searches for `clientRefreshRateX100`.

Expected: one assignment in Artemis session setup, complete test coverage, and no ABR endpoint integration.
