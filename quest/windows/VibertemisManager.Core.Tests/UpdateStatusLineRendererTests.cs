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
        Assert.Contains("Check for updates to retry", text);
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
        Assert.Equal("Check for updates", UpdateStatusLineRenderer.ActionLabel(Empty()));
        Assert.Equal("Checking…", UpdateStatusLineRenderer.ActionLabel(SnapshotChecking()));
    }

    [Fact]
    public void UpdateLabelOnAVerifiedCacheSaysInstallNotUpdate()
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
            Assert.Equal("Install update 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(s));
        }
        finally
        {
            try { System.IO.Directory.Delete(dir, recursive: true); } catch { }
        }
    }

    // ---- One update action, not two identical ones -------------------
    // The primary action is contextual, so the secondary manual Check
    // only earns its place while the primary is doing something else.

    [Fact]
    public void TheManualCheckIsOnlyOfferedWhileThePrimaryActionIsSomethingElse()
    {
        // Nothing on offer: the primary already says "Check for
        // updates", so a second button with that label would be a
        // duplicate rather than a choice.
        Assert.Equal("Check for updates", UpdateStatusLineRenderer.ActionLabel(Empty()));
        Assert.True(UpdateStatusLineRenderer.PrimaryIsCheck(Empty()));
        // An in-flight check owns the primary, so it is not a plain
        // check to offer alongside.
        Assert.False(UpdateStatusLineRenderer.PrimaryIsCheck(SnapshotChecking()));
        // A release on offer moves the primary to installing it.
        Assert.Equal("Update to 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(WithAvailable("0.1.0.10")));
        Assert.False(UpdateStatusLineRenderer.PrimaryIsCheck(WithAvailable("0.1.0.10")));
    }

    [Fact]
    public void ThePrimaryIsNeverPlainCheckWhileAnAttemptOwnsTheAction()
    {
        var failed = new UpdateFlowState(UpdateFlowStage.Failed, "0.1.0.10", "damaged", 0, 0, false, true, false, false);
        Assert.False(UpdateStatusLineRenderer.PrimaryIsCheck(Empty(), failed));
        Assert.Equal("Retry update to 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(Empty(), failed));
        Assert.True(UpdateStatusLineRenderer.PrimaryIsCheck(Empty(), UpdateFlowState.Idle));
    }

    [Fact]
    public void AVerifiedCacheMakesInstallTheOnlyPrimaryAction()
    {
        var release = new SignedRelease(
            SignedRelease.CurrentSequence + 1, "0.1.0.10", SignedRelease.Protocol,
            new ReleaseAsset("setup.exe", new Uri("https://example.com/setup.exe"), 8L, "0".PadRight(64, '0')));
        var file = System.IO.Path.Combine(System.IO.Path.GetTempPath(),
            "renderer-cache-" + Guid.NewGuid().ToString("N") + ".exe");
        System.IO.File.WriteAllBytes(file, new byte[8]);
        try
        {
            var cached = new UpdateRepository.Snapshot(release, null, null, release, null, null, file,
                DateTime.MinValue, DateTime.MinValue, null, false);
            Assert.False(UpdateStatusLineRenderer.PrimaryIsCheck(cached));
            Assert.Equal("Install update 0.1.0.10", UpdateStatusLineRenderer.ActionLabel(cached));
        }
        finally
        {
            try { System.IO.File.Delete(file); } catch { }
        }
    }

    private static UpdateRepository.Snapshot SnapshotChecking() => new(
        null, null, null, null, null, null, null,
        DateTime.MinValue, DateTime.MinValue, null, true);
}