// Tests for the persistent update-status line renderer. The
// renderer is the shared source of truth for the WinForms label
// and the Android hub badge / updates activity ladders, so the
// wording contract is pinned by these tests.
using System;
using VibertemisManager.Core.Update;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class UpdateStatusLineRendererTests
{
    private static UpdateRepository.Snapshot Empty() => new UpdateRepository.Snapshot(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, false);

    private static UpdateRepository.Snapshot WithSuccess(DateTime at) => new UpdateRepository.Snapshot(
        null, null, null, null, null, null, null,
        at, DateTime.MinValue, null, false);

    private static UpdateRepository.Snapshot WithFailure(DateTime at, string error) => new UpdateRepository.Snapshot(
        null, null, null, null, null, null, null,
        DateTime.MinValue, at, error, false);

    private static UpdateRepository.Snapshot Checking() => new UpdateRepository.Snapshot(
        null, null, null, null, null, null, null,
        DateTime.UtcNow, DateTime.MinValue, null, true);

    [Fact]
    public void DefaultEmptySnapshotShowsNotYetChecked()
    {
        Assert.Equal("Update status: not yet checked.", UpdateStatusLineRenderer.Render(Empty()));
    }

    [Fact]
    public void CheckingWinsOverEverythingElse()
    {
        var s = new UpdateRepository.Snapshot(null, null, null, null, null, null, null,
            DateTime.UtcNow, DateTime.MinValue, "ignored because checking wins", true);
        Assert.Equal("Update status: checking for updates\u2026", UpdateStatusLineRenderer.Render(s));
    }

    [Fact]
    public void FailureWithErrorShowsOfflineCopy()
    {
        var text = UpdateStatusLineRenderer.Render(WithFailure(DateTime.UtcNow, "network unreachable"));
        Assert.Contains("check unavailable", text);
        Assert.Contains("network unreachable", text);
        // The retry is the one Update action, so the error copy may not
        // send the owner looking for a check button that no longer
        // exists.
        Assert.Contains("Use Update to retry", text);
        Assert.DoesNotContain("Check for updates", text);
    }

    [Fact]
    public void SuccessWithNoAvailableAndNoDownloadedShowsUpToDate()
    {
        Assert.Equal("Update status: up to date.", UpdateStatusLineRenderer.Render(WithSuccess(DateTime.UtcNow)));
    }

    [Fact]
    public void AvailableButNoDownloadedShowsAvailableLine()
    {
        var release = new SignedRelease(
            Sequence: 9,
            Version: "0.1.0.9",
            NativeProtocol: SignedRelease.Protocol,
            Windows: new ReleaseAsset("VibertemisVR-HostManager-Setup-0.1.0.9.exe",
                new Uri("https://example.com/setup.exe"), 12_345_678L, "0".PadRight(64, '0')));
        var s = new UpdateRepository.Snapshot(
            release, null, null, null, null, null, null,
            DateTime.UtcNow, DateTime.MinValue, null, false);
        var text = UpdateStatusLineRenderer.Render(s);
        Assert.Contains("0.1.0.9 available", text);
        Assert.Contains("MB", text);
    }

    [Fact]
    public void DownloadedReadyWinsOverAvailable()
    {
        var downloaded = new SignedRelease(
            Sequence: 9, Version: "0.1.0.9", NativeProtocol: SignedRelease.Protocol,
            Windows: new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 1L, "0".PadRight(64, '0')));
        var available = new SignedRelease(
            Sequence: 10, Version: "0.1.0.10", NativeProtocol: SignedRelease.Protocol,
            Windows: new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 1L, "0".PadRight(64, '0')));
        var tmp = Path.Combine(Path.GetTempPath(), "vibt-statusline-" + Guid.NewGuid().ToString("N") + ".exe");
        File.WriteAllBytes(tmp, new byte[] { 1, 2, 3, 4 });
        try
        {
            var s = new UpdateRepository.Snapshot(
                available, null, null, downloaded, null, null, tmp,
                DateTime.UtcNow, DateTime.MinValue, null, false);
            var text = UpdateStatusLineRenderer.Render(s);
            Assert.Contains("verified and ready to install", text);
            Assert.Contains("0.1.0.9", text);
        }
        finally
        {
            try { File.Delete(tmp); } catch { }
        }
    }

    [Fact]
    public void AvailableLineBytesFormattingUsesFormatBytes()
    {
        var release = new SignedRelease(
            Sequence: 9, Version: "0.1.0.9", NativeProtocol: SignedRelease.Protocol,
            Windows: new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 10485760L, "0".PadRight(64, '0')));
        var s = new UpdateRepository.Snapshot(
            release, null, null, null, null, null, null,
            DateTime.UtcNow, DateTime.MinValue, null, false);
        var text = UpdateStatusLineRenderer.Render(s);
        Assert.Contains("10.0 MB", text);
    }

    [Fact]
    public void NeverClaimsUpToDateBeforeFirstSuccess()
    {
        var text = UpdateStatusLineRenderer.Render(Empty());
        Assert.DoesNotContain("up to date", text);
    }

    [Fact]
    public void SnapshotWithDownloadedFileButNullReleasedStillEmptyShowsNotYetChecked()
    {
        var s = new UpdateRepository.Snapshot(null, null, null, null, null, null, null,
            DateTime.MinValue, DateTime.MinValue, null, false);
        Assert.Equal("Update status: not yet checked.", UpdateStatusLineRenderer.Render(s));
    }

    // ---- One-click flow wording ---------------------------------------
    // The status line and the single update button share this wording
    // so the owner is never told an update finished when Windows is
    // merely about to run the installer.

    private static UpdateRepository.Snapshot WithAvailable(string version)
    {
        var release = new SignedRelease(
            SignedRelease.CurrentSequence + 1, version, SignedRelease.Protocol,
            new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 1048576L, "0".PadRight(64, '0')));
        return new UpdateRepository.Snapshot(release, null, null, null, null, null, null,
            DateTime.MinValue, DateTime.MinValue, null, false);
    }

    [Fact]
    public void AwaitingSystemIsNotReportedAsASuccess()
    {
        var flow = new UpdateFlowState(UpdateFlowStage.AwaitingSystem, "0.1.0.10", null, 0, 0, false, false, false, false);
        var text = UpdateStatusLineRenderer.Render(WithAvailable("0.1.0.10"), flow);
        Assert.Contains("Windows is installing update 0.1.0.10", text);
        Assert.DoesNotContain("completed", text);
        Assert.DoesNotContain("up to date", text);
    }

    [Fact]
    public void AFailedAttemptKeepsItsMessageVisibleForThePersistentLine()
    {
        var flow = new UpdateFlowState(UpdateFlowStage.Failed, "0.1.0.10",
            "The downloaded update is damaged. It was removed; download it again.", 0, 0, false, true, false, false);
        var text = UpdateStatusLineRenderer.Render(Empty(), flow);
        Assert.Contains("damaged", text);
        Assert.Contains("download it again", text);
    }

    [Fact]
    public void BlockedAttemptNamesWhatToCloseAndKeepsTheCachePromise()
    {
        var flow = new UpdateFlowState(UpdateFlowStage.Blocked, "0.1.0.10",
            "Close SteamVR and ALVR Dashboard (vrserver.exe), then choose Update again.", 0, 0, false, true, false, false);
        var text = UpdateStatusLineRenderer.Render(Empty(), flow);
        Assert.Contains("Close SteamVR", text);
        Assert.Contains("Update again", text);
    }

    [Fact]
    public void IdleFlowFallsBackToTheMetadataLadder()
    {
        var idle = UpdateFlowState.Idle;
        Assert.Equal(UpdateStatusLineRenderer.Render(Empty()), UpdateStatusLineRenderer.Render(Empty(), idle));
    }

    [Theory]
    [InlineData(UpdateFlowStage.Checking, "Checking\u2026")]
    [InlineData(UpdateFlowStage.AwaitingSystem, "Installing 0.1.0.10\u2026")]
    [InlineData(UpdateFlowStage.Failed, "Retry update to 0.1.0.10")]
    [InlineData(UpdateFlowStage.Blocked, "Retry update to 0.1.0.10")]
    [InlineData(UpdateFlowStage.Downloading, "Updating to 0.1.0.10\u2026")]
    [InlineData(UpdateFlowStage.Verifying, "Updating to 0.1.0.10\u2026")]
    [InlineData(UpdateFlowStage.HandingOff, "Updating to 0.1.0.10\u2026")]
    public void TheSingleUpdateButtonNeverPromisesTwoSteps(UpdateFlowStage stage, string expected)
    {
        var flow = new UpdateFlowState(stage, "0.1.0.10", null, 0, 0, false, false, false, false);
        Assert.Equal(expected, UpdateStatusLineRenderer.ActionLabel(Empty(), flow));
    }

    [Fact]
    public void UpdateLabelNamesTheVersionItWillInstallOrFetch()
    {
        Assert.Equal("Update to 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(WithAvailable("0.1.0.10")));
        // Nothing known yet: still one action, and the click itself
        // runs the check before it installs anything.
        Assert.Equal("Update", UpdateStatusLineRenderer.ActionLabel(Empty()));
        Assert.Equal("Checking\u2026", UpdateStatusLineRenderer.ActionLabel(SnapshotChecking()));
    }

    [Fact]
    public void UpdateLabelOnAVerifiedCacheIsStillTheSingleUpdateAction()
    {
        var dir = System.IO.Path.Combine(System.IO.Path.GetTempPath(), "renderer-" + Guid.NewGuid().ToString("N"));
        System.IO.Directory.CreateDirectory(dir);
        var file = System.IO.Path.Combine(dir, "setup.exe");
        System.IO.File.WriteAllBytes(file, new byte[8]);
        try
        {
            var release = new SignedRelease(
                SignedRelease.CurrentSequence + 1, "0.1.0.10", SignedRelease.Protocol,
                new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 8L, "0".PadRight(64, '0')));
            var s = new UpdateRepository.Snapshot(release, null, null, release, null, null, file,
                DateTime.MinValue, DateTime.MinValue, null, false);
            var label = UpdateStatusLineRenderer.ActionLabel(s);
            Assert.Equal("Update to 0.1.0.10", label);
            // The click that installs those cached bytes is the same
            // Update click, so the label must never present an install
            // as a second step the owner still has to reach.
            Assert.DoesNotContain("Install", label);
        }
        finally
        {
            try { System.IO.Directory.Delete(dir, recursive: true); } catch { }
        }
    }

    // ---- Exactly one update action ----------------------------------
    // There is no separate manual check control any more: the click
    // that cannot find anything to install checks for itself. So no
    // label may ever name a first step of two.

    [Fact]
    public void NoStateEverOffersASeparateCheckAction()
    {
        // At rest, on offer, mid-check, mid-download, and after a
        // failure: every one of these is the same single control.
        var resting = UpdateStatusLineRenderer.ActionLabel(Empty());
        Assert.Equal("Update", resting);
        Assert.DoesNotContain("Check for updates", resting);

        var offered = UpdateStatusLineRenderer.ActionLabel(WithAvailable("0.1.0.10"));
        Assert.DoesNotContain("Check for updates", offered);

        var checking = UpdateStatusLineRenderer.ActionLabel(SnapshotChecking());
        Assert.Equal("Checking\u2026", checking);
        Assert.DoesNotContain("Check for updates", checking);

        var failed = new UpdateFlowState(UpdateFlowStage.Failed, null, "network unreachable", 0, 0, false, true, false, false);
        Assert.Equal("Retry update", UpdateStatusLineRenderer.ActionLabel(Empty(), failed));
        Assert.DoesNotContain("Check for updates", UpdateStatusLineRenderer.ActionLabel(Empty(), failed));

        var idle = new UpdateFlowState(UpdateFlowStage.Idle, null, null, 0, 0, false, true, false, false);
        Assert.Equal("Update", UpdateStatusLineRenderer.ActionLabel(Empty(), idle));
    }

    [Fact]
    public void AFailedAttemptIsAlwaysOfferedAsARetryOfTheSameAction()
    {
        var busy = new UpdateFlowState(UpdateFlowStage.Blocked, "0.1.0.10",
            "Close SteamVR and ALVR Dashboard (vrserver.exe), then choose Update again.", 0, 0, false, true, false, false);
        Assert.Equal("Retry update to 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(Empty(), busy));
        var failed = new UpdateFlowState(UpdateFlowStage.Failed, "0.1.0.10",
            "Update check failed: network unreachable.", 0, 0, false, true, false, false);
        Assert.Equal("Retry update to 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(Empty(), failed));
    }

    [Fact]
    public void ACheckingAttemptSaysCheckingAndNotAVersion()
    {
        // No target is known while the click is resolving one, so the
        // label must not invent a version.
        var flow = new UpdateFlowState(UpdateFlowStage.Checking, null,
            "Checking for updates\u2026", 0, 0, true, false, true, true);
        Assert.Equal("Checking\u2026", UpdateStatusLineRenderer.ActionLabel(Empty(), flow));
        Assert.Equal("Update status: Checking for updates\u2026", UpdateStatusLineRenderer.Render(Empty(), flow));
        // With no message of its own the stage phrase still says it is
        // checking, which is the honest description of the stage.
        var bare = new UpdateFlowState(UpdateFlowStage.Checking, null, null, 0, 0, true, false, true, true);
        Assert.Equal("Update status: checking for updates\u2026", UpdateStatusLineRenderer.Render(Empty(), bare));
    }

    private static UpdateRepository.Snapshot SnapshotChecking() => new(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, true);
}
