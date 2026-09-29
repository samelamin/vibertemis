// Tests for the nonce LRU and rate limiter. The nonce LRU is
// the most security-critical piece in this file: a same-nonce
// replay MUST be rejected even after cache churn, and a
// capacity-full cache MUST reject (not evict) to keep the
// invariant.
package state

import (
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestNonceLRU_AcceptsFreshAndRejectsReplay(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	lru := NewNonceLRU(8, 30*time.Second, func() time.Time { return now })
	ts := now
	dup, err := lru.CheckAndAdd("nonce-a", ts)
	if err != nil {
		t.Fatalf("first add: %v", err)
	}
	if dup {
		t.Fatal("first add must not be a duplicate")
	}
	dup, err = lru.CheckAndAdd("nonce-a", ts)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !dup {
		t.Fatal("replay must be detected")
	}
}

func TestNonceLRU_RejectsOnFull(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	lru := NewNonceLRU(4, 30*time.Second, func() time.Time { return now })
	ts := now
	for i := 0; i < 4; i++ {
		dup, err := lru.CheckAndAdd("nonce-"+string(rune('a'+i)), ts)
		if err != nil {
			t.Fatalf("add %d: %v", i, err)
		}
		if dup {
			t.Fatalf("add %d unexpectedly a dup", i)
		}
	}
	// 5th add: capacity full, no expired entries → reject.
	dup, err := lru.CheckAndAdd("nonce-e", ts)
	if err == nil {
		t.Fatal("expected ErrCacheFull on 5th add, got nil")
	}
	if dup {
		t.Fatal("ErrCacheFull must not report as duplicate")
	}
}

func TestNonceLRU_RejectsEvictionUnderChurn(t *testing.T) {
	// Phase 1 invariant: same-nonce replay cannot succeed after
	// cache churn. The previous "evict oldest" implementation
	// allowed this attack; the reject-full implementation
	// prevents it.
	now := time.Unix(1_000_000, 0)
	lru := NewNonceLRU(4, 30*time.Second, func() time.Time { return now })
	ts := now
	// Add the target nonce.
	if dup, err := lru.CheckAndAdd("target", ts); err != nil || dup {
		t.Fatalf("seed: dup=%v err=%v", dup, err)
	}
	// Churn the cache with other nonces; because the LRU is at
	// capacity, every churn is rejected (NOT evicting).
	for i := 0; i < 100; i++ {
		_, _ = lru.CheckAndAdd("churn", ts)
	}
	// Replay the target nonce — must still be detected.
	dup, err := lru.CheckAndAdd("target", ts)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !dup {
		t.Fatal("replay after churn must be detected; the LRU must not have evicted the target")
	}
}

// TestNonceLRU_FutureTimestampRetainedForFullValidity is the
// regression for the boundary bug: a nonce added with a
// future-timestamp T+maxSkew at wall-clock T must remain
// detectable for the entire [T, T+2*maxSkew] interval during
// which the server still accepts requests with that
// timestamp. Cache expiry based on add-time (T) instead of
// request-time (T+maxSkew) would let a same-nonce replay at
// T+1 succeed (the nonce is gone but the server still
// accepts the original timestamp).
func TestNonceLRU_FutureTimestampRetainedForFullValidity(t *testing.T) {
	var now time.Time
	clock := func() time.Time { return now }
	lru := NewNonceLRU(8, 30*time.Second, clock)
	now = time.Unix(1_000_000, 0)
	futureTs := now.Add(30 * time.Second) // |delta| == maxSkew, accepted
	// Insert with future timestamp.
	if dup, err := lru.CheckAndAdd("future", futureTs); err != nil || dup {
		t.Fatalf("seed: dup=%v err=%v", dup, err)
	}
	// Advance wall time past the add-time + maxSkew (= T+30)
	// but well within the request's ts + maxSkew (= T+60).
	now = now.Add(31 * time.Second)
	// Replay with the SAME future timestamp: server still
	// accepts (|31 - 30| == 1 <= maxSkew). The cache must
	// still hold the entry — otherwise this is the bug.
	dup, err := lru.CheckAndAdd("future", futureTs)
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if !dup {
		t.Fatal("future-ts nonce must remain detectable across [ts - maxSkew, ts + maxSkew]; add-time-based expiry forgot it too early")
	}
}

// TestNonceLRU_BoundaryExpiryInclusive — at exactly
// ts + maxSkew the nonce must still be in the cache so a
// replay with the original ts is still detected.
func TestNonceLRU_BoundaryExpiryInclusive(t *testing.T) {
	base := time.Unix(1_000_000, 0)
	var now time.Time
	clock := func() time.Time { return now }
	lru := NewNonceLRU(8, 30*time.Second, clock)
	now = base
	ts := base
	if dup, err := lru.CheckAndAdd("n", ts); err != nil || dup {
		t.Fatalf("seed: dup=%v err=%v", dup, err)
	}
	// Advance to EXACTLY ts + maxSkew.
	now = base.Add(30 * time.Second)
	dup, err := lru.CheckAndAdd("n", ts)
	if err != nil {
		t.Fatalf("boundary: %v", err)
	}
	if !dup {
		t.Fatal("nonce must still be in cache at exactly ts + maxSkew (inclusive)")
	}
	// One nanosecond past ts + maxSkew: now > ts+maxSkew strictly,
	// so the server would reject the request for skew, and the
	// cache has dropped it.
	now = base.Add(30*time.Second + time.Nanosecond)
	dup, err = lru.CheckAndAdd("n", ts)
	if err != nil {
		t.Fatalf("past: %v", err)
	}
	if dup {
		t.Fatal("nonce must be pruned once now > ts + maxSkew strictly")
	}
}

// TestNonceLRU_OutOfOrderExpiry — entries with different
// timestamps expire independently. Inserting a "younger"
// entry first must not delay expiry of an "older" entry.
func TestNonceLRU_OutOfOrderExpiry(t *testing.T) {
	base := time.Unix(1_000_000, 0)
	var now time.Time
	clock := func() time.Time { return now }
	lru := NewNonceLRU(8, 30*time.Second, clock)
	now = base
	// Add an "older" entry with ts = base + 5.
	if dup, err := lru.CheckAndAdd("older", base.Add(5*time.Second)); err != nil || dup {
		t.Fatalf("older: dup=%v err=%v", dup, err)
	}
	// Add a "younger" entry with ts = base + 10.
	if dup, err := lru.CheckAndAdd("younger", base.Add(10*time.Second)); err != nil || dup {
		t.Fatalf("younger: dup=%v err=%v", dup, err)
	}
	// At now = base + 40: older is past ts+maxSkew = base+35
	// (pruned), younger is past ts+maxSkew = base+40 (still
	// inclusive, so still in cache).
	now = base.Add(40 * time.Second)
	dup, err := lru.CheckAndAdd("older", base.Add(5*time.Second))
	if err != nil {
		t.Fatalf("older past: %v", err)
	}
	if dup {
		t.Fatal("older entry must be pruned at now > ts + maxSkew (older's ts=base+5)")
	}
	dup, err = lru.CheckAndAdd("younger", base.Add(10*time.Second))
	if err != nil {
		t.Fatalf("younger: %v", err)
	}
	if !dup {
		t.Fatal("younger entry must remain at ts+maxSkew == now (inclusive)")
	}
	// At now = base + 41: younger is now strictly past.
	now = base.Add(41 * time.Second)
	dup, err = lru.CheckAndAdd("younger", base.Add(10*time.Second))
	if err != nil {
		t.Fatalf("younger past: %v", err)
	}
	if dup {
		t.Fatal("younger entry must be pruned once now > ts + maxSkew strictly")
	}
}

// TestNonceLRU_BoundedPruneStripsAllStrictlyExpired makes
// sure a single CheckAndAdd call drains every strictly
// expired entry, regardless of insertion order.
func TestNonceLRU_BoundedPruneStripsAllStrictlyExpired(t *testing.T) {
	base := time.Unix(1_000_000, 0)
	var now time.Time
	clock := func() time.Time { return now }
	lru := NewNonceLRU(8, 30*time.Second, clock)
	now = base
	for i := 0; i < 5; i++ {
		if dup, err := lru.CheckAndAdd(fmt.Sprintf("n-%d", i), base); err != nil || dup {
			t.Fatalf("seed %d: dup=%v err=%v", i, dup, err)
		}
	}
	// Advance past maxSkew for all 5 entries.
	now = base.Add(31 * time.Second)
	// A new call must strip them all.
	if dup, err := lru.CheckAndAdd("fresh", base); err != nil || dup {
		t.Fatalf("fresh: dup=%v err=%v", dup, err)
	}
	if lru.Size() != 1 {
		t.Fatalf("expected 1 entry after full prune, got %d", lru.Size())
	}
}

func TestNonceLRU_PrunesExpired(t *testing.T) {
	base := time.Unix(1_000_000, 0)
	var now time.Time
	clock := func() time.Time { return now }
	lru := NewNonceLRU(8, 30*time.Second, clock)
	now = base
	if dup, err := lru.CheckAndAdd("a", base); err != nil || dup {
		t.Fatalf("seed a: dup=%v err=%v", dup, err)
	}
	// Advance past maxSkew.
	now = base.Add(31 * time.Second)
	if dup, err := lru.CheckAndAdd("a", base); err != nil || dup {
		t.Fatalf("after skew: dup=%v err=%v", dup, err)
	}
	if lru.Size() > 1 {
		t.Fatalf("expired entry not pruned: size=%d", lru.Size())
	}
}

func TestNonceLRU_ConcurrentSafe(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	lru := NewNonceLRU(1024, 30*time.Second, func() time.Time { return now })
	ts := now
	var wg sync.WaitGroup
	for i := 0; i < 16; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := 0; j < 256; j++ {
				_, _ = lru.CheckAndAdd("n", ts)
			}
		}()
	}
	wg.Wait()
	dup, err := lru.CheckAndAdd("n", ts)
	if err != nil {
		t.Fatalf("final replay: %v", err)
	}
	if !dup {
		t.Fatal("concurrent adds: final replay not detected")
	}
}

func TestRateLimiter_BurstThenThrottle(t *testing.T) {
	now := time.Unix(1_000_000, 0)
	clock := func() time.Time { return now }
	rl := NewRateLimiter(3, 30*time.Second, clock)
	for i := 0; i < 3; i++ {
		if !rl.Allow() {
			t.Fatalf("burst %d: should allow", i)
		}
	}
	if rl.Allow() {
		t.Fatal("4th call in window must be rejected")
	}
	// Advance by 10s (one token's worth of refill).
	now = now.Add(10 * time.Second)
	if !rl.Allow() {
		t.Fatal("after one refill, one call must be allowed")
	}
	if rl.Allow() {
		t.Fatal("after one refill, the second call must be rejected")
	}
}
