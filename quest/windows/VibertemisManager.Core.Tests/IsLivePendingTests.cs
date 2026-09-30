// IsLivePending helper tests. The helper is the production
// gate MainForm uses to decide whether the pairing notice is
// outstanding and to drive the deferred update re-render.
// These tests exercise the actual helper directly, including
// the Unix-seconds-vs-tick construction bug that previously
// treated every real pending as expired.
using System;
using VibertemisManager.Core.Pairing;
using Xunit;

namespace VibertemisManager.Core.Tests;

public sealed class IsLivePendingTests
{
    private static readonly DateTime UtcNow = new(2026, 6, 15, 12, 0, 0, DateTimeKind.Utc);
    private static readonly long NowUnix = new DateTimeOffset(UtcNow, TimeSpan.Zero).ToUnixTimeSeconds();

    private static ReceivingSnapshot Inputs(
        bool companionRunning = true,
        bool receiving = true,
        bool suppressed = false)
        => new(
            Receiving: receiving,
            Suppressed: suppressed,
            SuppressUntilUtc: null,
            CompanionRunning: companionRunning,
            AsOfUtc: UtcNow);

    private static CoordinatorPending Pending(
        long? expiresUnix = null,
        string state = "pending",
        string? sessionId = "abc123")
        => new(
            Open: true,
            Receiving: true,
            Suppressed: false,
            SuppressUntilUnix: 0L,
            SessionId: sessionId,
            Code: "1111-2222-3333-4444",
            State: state,
            Expires: expiresUnix ?? (NowUnix + 120),
            LeaseExpiresUnix: expiresUnix ?? (NowUnix + 120),
            TtlSeconds: 120,
            Devices: 0);

    [Fact]
    public void FutureExpiryWithRunningCompanionIsLive()
    {
        Assert.True(ApprovalController.IsLivePending(
            Pending(expiresUnix: NowUnix + 60),
            Inputs(companionRunning: true, receiving: true),
            UtcNow));
    }

    [Fact]
    public void PastExpiryIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(expiresUnix: NowUnix - 30),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void ExactlyExpiredExpiryIsNotLive()
    {
        // snap.Expires == nowUnix is the boundary; the helper
        // treats it as already expired so the owner does not
        // see a 0-second-remaining request.
        Assert.False(ApprovalController.IsLivePending(
            Pending(expiresUnix: NowUnix),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void ZeroExpiryIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(expiresUnix: 0L),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void NonPendingStateIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(state: "approved"),
            Inputs(),
            UtcNow));
        Assert.False(ApprovalController.IsLivePending(
            Pending(state: "denied"),
            Inputs(),
            UtcNow));
        Assert.False(ApprovalController.IsLivePending(
            Pending(state: "closed"),
            Inputs(),
            UtcNow));
        Assert.False(ApprovalController.IsLivePending(
            Pending(state: "waiting"),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void EmptySessionIdIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(sessionId: null),
            Inputs(),
            UtcNow));
        Assert.False(ApprovalController.IsLivePending(
            Pending(sessionId: ""),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void OfflineCompanionIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(),
            Inputs(companionRunning: false),
            UtcNow));
    }

    [Fact]
    public void ReceivingDisabledIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(),
            Inputs(receiving: false),
            UtcNow));
    }

    [Fact]
    public void SuppressedIsNotLive()
    {
        Assert.False(ApprovalController.IsLivePending(
            Pending(),
            Inputs(suppressed: true),
            UtcNow));
    }

    [Fact]
    public void RealisticFutureExpiryPasses()
    {
        // Regression for the Unix-seconds-as-tick bug. A real
        // pending with Expires=1800000000 (Unix seconds for
        // ~2027) used to be treated as year-0001 because the
        // construction interpreted the long as DateTime ticks.
        long realisticExpiry = NowUnix + 180; // three minutes
        Assert.True(ApprovalController.IsLivePending(
            Pending(expiresUnix: realisticExpiry),
            Inputs(),
            UtcNow));
    }

    [Fact]
    public void LargeFutureExpiryStillPasses()
    {
        // Even a far-future Unix timestamp must not overflow
        // when compared against nowUnix.
        long farFuture = NowUnix + 60L * 60 * 24 * 365; // 1 year
        Assert.True(ApprovalController.IsLivePending(
            Pending(expiresUnix: farFuture),
            Inputs(),
            UtcNow));
    }
}