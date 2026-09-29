// Package state holds the in-memory request state: a reject-full
// nonce LRU with bounded expiry, and a per-IP rate limiter.
//
// The nonce LRU is intentionally NOT a generic cache:
//   - When the cache is at capacity and a new nonce arrives, the
//     request is REJECTED. We do NOT evict. Evicting would let a
//     same-nonce replay succeed after churn, which is exactly
//     the vulnerability the LRU is meant to prevent.
//   - Each nonce carries the request timestamp that added it.
//     A nonce is valid for the entire interval during which a
//     server would still accept the request: a request with
//     timestamp `ts` is accepted while |now - ts| <= maxSkew,
//     so the validity window is [ts - maxSkew, ts + maxSkew].
//     The cache MUST retain the nonce through ts + maxSkew
//     (inclusive). Forgetting earlier lets a same-nonce replay
//     succeed once the original request's timestamp has aged
//     out but the server would still accept a forged request
//     with the original timestamp.
//   - The cache prunes strictly-expired entries (now > ts +
//     maxSkew). Expiry is computed from the request timestamp,
//     NOT from the wall-clock add time, so a future-ts
//     request still covers its full validity window.
//
// The rate limiter is a token bucket: burst tokens at the start,
// refill at burst/window rate, each call consumes one.
package state

import (
	"errors"
	"sync"
	"time"
)

// ErrCacheFull is returned by CheckAndAdd when the cache is at
// capacity and none of the stored entries have expired. The
// caller MUST reject the request; we refuse to evict.
var ErrCacheFull = errors.New("nonce cache full; refusing to evict")

// NonceEntry is a stored nonce + its originating request
// timestamp.
type NonceEntry struct {
	nonce string
	ts    time.Time
}

// NonceLRU rejects on capacity overflow. Operations are
// safe for concurrent use.
//
// Expiry is by REQUEST-TIME: a stored nonce expires when
// now > ts + maxSkew. The add-time (now) is intentionally
// NOT used for expiry so a future-timestamp request retains
// its nonce for the entire [ts - maxSkew, ts + maxSkew]
// validity window (Codex #18 boundary bug). Out-of-order
// arrival is fine: each entry expires independently by its
// own ts + maxSkew.
type NonceLRU struct {
	mu      sync.Mutex
	cap     int
	entries map[string]nonceEntry
	order   []string
	maxSkew time.Duration
	now     func() time.Time
}

type nonceEntry struct {
	ts time.Time
}

// NewNonceLRU returns a LRU with the given capacity. maxSkew
// bounds how long a nonce remains valid. now is for tests.
func NewNonceLRU(capacity int, maxSkew time.Duration, now func() time.Time) *NonceLRU {
	if capacity <= 0 {
		capacity = 1024
	}
	if maxSkew <= 0 {
		maxSkew = 30 * time.Second
	}
	if now == nil {
		now = time.Now
	}
	return &NonceLRU{
		cap:     capacity,
		entries: make(map[string]nonceEntry, capacity),
		order:   make([]string, 0, capacity),
		maxSkew: maxSkew,
		now:     now,
	}
}

// MaxSkew returns the configured maximum allowed clock skew.
func (l *NonceLRU) MaxSkew() time.Duration { return l.maxSkew }

// Capacity returns the configured capacity.
func (l *NonceLRU) Capacity() int { return l.cap }

// CheckAndAdd prunes expired entries (by add-time) and
// atomically decides whether to accept the nonce.
//
// Returns:
//
//   - (true, nil): the nonce was a duplicate of a stored
//     non-expired entry. The caller MUST reject the request
//     as a replay.
//   - (false, nil): the nonce was fresh; it has been stored.
//     The caller MAY proceed.
//   - (false, ErrCacheFull): the cache is at capacity AND none
//     of the stored entries have expired. The caller MUST
//     reject the request. We refuse to evict.
//
// The request timestamp (ts) IS used for expiry: an entry
// expires when now > ts + maxSkew. The validity window for a
// request with timestamp `ts` is [ts - maxSkew, ts + maxSkew]
// (the server accepts any now in that range), and the cache
// MUST retain the nonce across that entire window or a
// forged replay can succeed once the original request's
// timestamp has aged past maxSkew.
func (l *NonceLRU) CheckAndAdd(nonce string, ts time.Time) (bool, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.now()
	// Prune strictly-expired entries by request ts. ts +
	// maxSkew is INCLUSIVE — at exactly ts + maxSkew the
	// server still accepts a request with ts, so the nonce
	// must still be in the cache to detect the replay.
	for _, key := range l.order {
		e, ok := l.entries[key]
		if !ok {
			continue
		}
		if now.Sub(e.ts) > l.maxSkew {
			delete(l.entries, key)
		}
	}
	// Rebuild the order list in place, dropping pruned keys.
	keep := l.order[:0]
	for _, key := range l.order {
		if _, ok := l.entries[key]; ok {
			keep = append(keep, key)
		}
	}
	l.order = keep
	if _, dup := l.entries[nonce]; dup {
		return true, nil
	}
	if len(l.entries) >= l.cap {
		// Reject on full. We do NOT evict.
		return false, ErrCacheFull
	}
	l.entries[nonce] = nonceEntry{ts: ts}
	l.order = append(l.order, nonce)
	return false, nil
}

// Size returns the current count of stored nonces.
func (l *NonceLRU) Size() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.entries)
}

// RateLimiter is a per-IP token bucket. Tests inject now.
type RateLimiter struct {
	mu        sync.Mutex
	burst     int
	refillDur time.Duration
	tokens    int
	last      time.Time
	now       func() time.Time
}

// NewRateLimiter returns a limiter with the given burst and
// window (refilled at burst/window rate).
func NewRateLimiter(burst int, window time.Duration, now func() time.Time) *RateLimiter {
	if burst <= 0 {
		burst = 3
	}
	if window <= 0 {
		window = 30 * time.Second
	}
	if now == nil {
		now = time.Now
	}
	return &RateLimiter{
		burst:     burst,
		refillDur: window / time.Duration(burst),
		tokens:    burst,
		last:      now(),
		now:       now,
	}
}

// Allow consumes one token if available.
func (r *RateLimiter) Allow() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	now := r.now()
	elapsed := now.Sub(r.last)
	if elapsed >= r.refillDur {
		refills := int(elapsed / r.refillDur)
		if refills > r.burst {
			refills = r.burst
		}
		r.tokens += refills
		if r.tokens > r.burst {
			r.tokens = r.burst
		}
		r.last = now
	}
	if r.tokens <= 0 {
		return false
	}
	r.tokens--
	return true
}

// Tokens returns the current token count.
func (r *RateLimiter) Tokens() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.tokens
}

// RateLimiterFactory maintains a per-key RateLimiter, with a
// bounded number of buckets. When the cap is reached, the
// oldest bucket is evicted.
type RateLimiterFactory struct {
	mu       sync.Mutex
	burst    int
	window   time.Duration
	now      func() time.Time
	cap      int
	buckets  map[string]*RateLimiter
	lastSeen map[string]time.Time
}

// NewRateLimiterFactory returns a factory that creates one
// RateLimiter per key. burst/window are the per-key parameters.
// cap is the maximum number of buckets retained; older buckets
// are evicted FIFO when the cap is reached.
func NewRateLimiterFactory(burst int, window time.Duration, cap int, now func() time.Time) *RateLimiterFactory {
	if now == nil {
		now = time.Now
	}
	if cap <= 0 {
		cap = 1024
	}
	return &RateLimiterFactory{
		burst:    burst,
		window:   window,
		now:      now,
		cap:      cap,
		buckets:  make(map[string]*RateLimiter, cap),
		lastSeen: make(map[string]time.Time, cap),
	}
}

// Allow consumes one token from the limiter for key.
func (f *RateLimiterFactory) Allow(key string) bool {
	f.mu.Lock()
	rl, ok := f.buckets[key]
	if !ok {
		if len(f.buckets) >= f.cap {
			// Evict the oldest bucket.
			var oldestKey string
			var oldestTime time.Time
			first := true
			for k, t := range f.lastSeen {
				if first || t.Before(oldestTime) {
					oldestKey = k
					oldestTime = t
					first = false
				}
			}
			if oldestKey != "" {
				delete(f.buckets, oldestKey)
				delete(f.lastSeen, oldestKey)
			}
		}
		rl = NewRateLimiter(f.burst, f.window, f.now)
		f.buckets[key] = rl
	}
	f.lastSeen[key] = f.now()
	f.mu.Unlock()
	return rl.Allow()
}

// Size returns the current number of tracked buckets.
func (f *RateLimiterFactory) Size() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return len(f.buckets)
}
