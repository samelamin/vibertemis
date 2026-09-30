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
}