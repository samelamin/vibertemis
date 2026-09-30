package com.vibertemis.quest.pcvr;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;
import java.io.IOException;
import java.net.Inet4Address;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, sequential, cancellable NSD browse for the Setup VR UX.
 *
 * <p>DNS-SD here returns UNTRUSTED hints only. The candidate list is
 * what the headset shows to the user; trust is established separately
 * through the pinned TLS identity handshake. The TXT pin never
 * shortcuts that handshake: a forged TXT pin grants nothing.
 *
 * <p>Lifecycle bounds:
 * <ul>
 *   <li>Maximum {@link #MAX_SERVICES} unique services are admitted at
 *       ingress; the admission count includes invalid services, so a
 *       malicious burst of uniquely-named invalid services cannot
 *       exceed the cap.</li>
 *   <li>The pending resolve queue is bounded to {@link #MAX_SERVICES}
 *       so a hostile burst cannot exhaust heap.</li>
 *   <li>8 second total browse budget shared by discovery and
 *       resolve; sequential resolve so a noisy LAN cannot fan out.</li>
 *   <li>{@link #cancel()} stops NSD discovery and unblocks the result
 *       poll with a short bounded wait, so cancellation never holds the
 *       caller for the full budget.</li>
 * </ul>
 *
 * <p>Filtering:
 * <ul>
 *   <li>Protocol string must match the canonical preview8 protocol id.</li>
 *   <li>Address must be IPv4. Unspecified, loopback, multicast,
 *       and public addresses are REJECTED. RFC1918 / link-local
 *       unicast / CGNAT 100.64/10 are ACCEPTED.</li>
 *   <li>Display name is the TXT {@code name} (display-only) when
 *       present, falling back to the service name. Control and
 *       bidi-override characters are replaced with {@code _}; the
 *       normal Unicode letters are preserved; the result is
 *       length-capped at {@link #MAX_NAME_LENGTH}.</li>
 * </ul>
 *
 * <p>Injectable seam: the {@link Factory} constructor takes a
 * factory that yields a {@link BrowseDriver}; tests inject a fake
 * driver that simulates NSD without touching the system service.
 * The default constructor uses the real {@link Context#NSD_SERVICE}.
 */
public final class VrSetupDiscovery {

    private static final String TAG = "VrSetupDiscovery";

    /** Canonical service type from the Windows companion. */
    public static final String SERVICE = "_vibertemis-vr._tcp.";

    /** Canonical protocol id from the Windows companion TXT. */
    public static final String PROTOCOL = "20.14.1-vibertemis-pyro.1";

    /** Bounded browse / resolve budget. */
    public static final long BROWSE_BUDGET_MS = 8000L;

    /** Bounded maximum number of admitted unique services. */
    public static final int MAX_SERVICES = 16;

    /** Maximum length of the sanitized display name. */
    public static final int MAX_NAME_LENGTH = 32;

    /** Short bounded wait used during cancellation so the result
     *  poll never blocks the caller for the full browse budget. */
    private static final long CANCEL_POLL_MS = 25L;

    /** One untrusted mDNS service entry. */
    public static final class Candidate {
        public final String name;
        public final String address;
        public final int port;

        public Candidate(String name, String address, int port) {
            this.name = name;
            this.address = address;
            this.port = port;
        }

        /** Render as {@code "<name> (host[:port])"} when the name
         *  is non-empty, else as {@code host[:port]}. A nondefault
         *  VR port is appended so the user can tell which custom
         *  port the PC is advertising. The default VR port
         *  ({@link #DEFAULT_VR_PORT}) is omitted from the display
         *  to avoid noise. */
        public String display() {
            String hostPort = address;
            if (port > 0 && port != DEFAULT_VR_PORT) {
                hostPort = address + ":" + port;
            }
            if (name == null || name.isEmpty()) return hostPort;
            return name + " (" + hostPort + ")";
        }
    }

    /** Abstract NSD driver so tests can inject a fake. */
    public interface BrowseDriver {
        void start(NsdManager.DiscoveryListener listener);
        void stop(NsdManager.DiscoveryListener listener);
        void resolve(NsdServiceInfo info, NsdManager.ResolveListener listener);
    }

    /** Default driver backed by the real {@link NsdManager}. */
    public static final class SystemDriver implements BrowseDriver {
        private final NsdManager manager;
        public SystemDriver(Context context) {
            this.manager = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        }
        @Override public void start(NsdManager.DiscoveryListener listener) {
            if (manager == null) throw new IllegalStateException("NSD unavailable");
            manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        }
        @Override public void stop(NsdManager.DiscoveryListener listener) {
            if (manager == null) return;
            try { manager.stopServiceDiscovery(listener); }
            catch (RuntimeException ignored) { }
        }
        @Override public void resolve(NsdServiceInfo info, NsdManager.ResolveListener listener) {
            if (manager == null) throw new IllegalStateException("NSD unavailable");
            manager.resolveService(info, listener);
        }
    }

    /** Factory seam: tests inject a fake BrowseDriver. */
    public interface Factory {
        BrowseDriver create();
    }

    /**
     * Monotonic wall-clock seam. Production uses
     * {@link android.os.SystemClock#elapsedRealtime()}; tests
     * inject a fake clock so the bounded 8 s budget can be expired
     * deterministically without waiting on the Robolectric
     * (frozen) clock.
     */
    public interface Clock {
        long nowRealtime();
        Clock SYSTEM = new Clock() {
            @Override public long nowRealtime() {
                return android.os.SystemClock.elapsedRealtime();
            }
        };
    }

    private final Factory factory;
    private final Clock clock;

    /** Construct with a real {@link Context}-backed driver. */
    public VrSetupDiscovery(Context context) {
        this(context, Clock.SYSTEM);
    }

    /** Construct with a real {@link Context}-backed driver and a
     *  custom clock (test seam). */
    public VrSetupDiscovery(Context context, Clock clock) {
        this.clock = clock != null ? clock : Clock.SYSTEM;
        this.factory = new Factory() {
            @Override public BrowseDriver create() {
                return new SystemDriver(context);
            }
        };
    }

    /** Construct with an injected factory (test seam). */
    public VrSetupDiscovery(Factory factory) {
        this(factory, Clock.SYSTEM);
    }

    /** Construct with an injected factory and clock (test seam). */
    public VrSetupDiscovery(Factory factory, Clock clock) {
        if (factory == null) throw new NullPointerException("factory");
        if (clock == null) throw new NullPointerException("clock");
        this.factory = factory;
        this.clock = clock;
    }

    private volatile boolean cancelled;

    /** Cancellation token. {@link #browse()} observes this between
     *  resolve callbacks and between poll iterations; when true, the
     *  driver is stopped and any further resolve callbacks are
     *  ignored. The result poll uses a short bounded wait so the
     *  caller returns within ~CANCEL_POLL_MS instead of blocking on
     *  the full browse budget. */
    public void cancel() {
        cancelled = true;
    }

    /**
     * Bounded, sequential, cancellable browse. Returns every
     * accepted candidate that arrived within {@link #BROWSE_BUDGET_MS}.
     * Stops NSD discovery at the end, on cancel, or on a driver
     * failure, and ignores any late resolve callback that lands
     * after the cancellation token flipped.
     */
    public java.util.List<Candidate> browse() throws IOException {
        BrowseDriver driver;
        try { driver = factory.create(); }
        catch (RuntimeException e) { throw new IOException("LAN discovery unavailable", e); }
        if (driver == null) throw new IOException("LAN discovery unavailable");
        java.util.List<Candidate> out = new java.util.ArrayList<Candidate>(MAX_SERVICES);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean resolving = new AtomicBoolean();
        // Admission counter: incremented at ingress for every
        // unique service name regardless of whether the service is
        // valid. A hostile burst of uniquely-named invalid services
        // therefore cannot exceed MAX_SERVICES.
        final AtomicInteger admitted = new AtomicInteger();
        // Bounded pending queue: a hostile burst cannot grow it
        // past MAX_SERVICES entries.
        final LinkedBlockingQueue<NsdServiceInfo> pending =
                new LinkedBlockingQueue<NsdServiceInfo>(MAX_SERVICES);
        // Bounded result queue: capped at MAX_SERVICES.
        final LinkedBlockingQueue<Candidate> results =
                new LinkedBlockingQueue<Candidate>(MAX_SERVICES);
        // Unique service names seen at ingress. Bounded by the
        // admitted counter above.
        final Set<String> seen =
                Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        final NsdManager.DiscoveryListener listener =
                new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) { }
            @Override public void onDiscoveryStopped(String type) {
                // The framework fires onDiscoveryStopped after the
                // last onServiceLost (or after stopServiceDiscovery
                // returns). No further callbacks will arrive on this
                // listener. Closing the loop here lets a polite
                // driver shut down without waiting for the budget,
                // and lets tests drive the discovery to completion
                // without touching the system clock.
                closed.set(true);
            }
            @Override public void onServiceLost(NsdServiceInfo info) { }
            @Override public void onStartDiscoveryFailed(String type, int code) {
                Log.w(TAG, "discovery start failed: " + code);
                closed.set(true);
            }
            @Override public void onStopDiscoveryFailed(String type, int code) { }
            @Override public void onServiceFound(NsdServiceInfo info) {
                if (closed.get() || cancelled) return;
                if (info == null) return;
                String key = info.getServiceName();
                if (key == null) return;
                // Admit at ingress: even invalid services count
                // against the cap so a hostile burst cannot grow
                // the pending / seen sets unbounded.
                int current;
                do {
                    current = admitted.get();
                    if (current >= MAX_SERVICES) return;
                } while (!admitted.compareAndSet(current, current + 1));
                if (!seen.add(key)) {
                    admitted.decrementAndGet();
                    return;
                }
                if (!pending.offer(info)) {
                    Log.w(TAG, "pending queue full, dropping " + key);
                }
                drainPending();
            }

            private void drainPending() {
                if (closed.get() || cancelled) return;
                while (true) {
                    if (closed.get() || cancelled) { resolving.set(false); return; }
                    if (!resolving.compareAndSet(false, true)) return;
                    final NsdServiceInfo info = pending.poll();
                    if (info == null) {
                        // Lost-wakeup guard. A service may have been
                        // offered to {@link #pending} between our
                        // pending.poll() returning null and the
                        // moment we set resolving=false. The
                        // offering thread's drainPending would have
                        // bailed on resolving=true and never
                        // re-fired. Release resolving and re-attempt
                        // if pending is non-empty so the service
                        // is not silently dropped. Sequential
                        // resolve and the 16-ingress / 8 s / 25 ms
                        // bounds are preserved because the only
                        // path that continues is the one that
                        // observes a freshly-offered service.
                        resolving.set(false);
                        if (!closed.get() && !cancelled && !pending.isEmpty()) continue;
                        return;
                    }
                    final NsdManager.ResolveListener rl = new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo service, int code) {
                            if (closed.get() || cancelled) { resolving.set(false); return; }
                            resolving.set(false);
                            drainPending();
                        }
                        @Override public void onServiceResolved(NsdServiceInfo service) {
                            try {
                                if (closed.get() || cancelled) return;
                                Candidate c = sanitize(service);
                                if (c == null) return;
                                if (!results.offer(c)) {
                                    Log.w(TAG, "result queue full, dropping " + c.address);
                                }
                            } finally {
                                resolving.set(false);
                                drainPending();
                            }
                        }
                    };
                    try { driver.resolve(info, rl); }
                    catch (RuntimeException e) {
                        resolving.set(false);
                        drainPending();
                    }
                    return;
                }
            }
        };
        try { driver.start(listener); }
        catch (RuntimeException e) {
            try { driver.stop(listener); } catch (RuntimeException ignored) { }
            throw new IOException("LAN discovery unavailable", e);
        }
        try {
            long end = clock.nowRealtime() + BROWSE_BUDGET_MS;
            // Always use a short bounded wait so cancellation wakes
            // the poll within CANCEL_POLL_MS even when the caller
            // signals cancel AFTER browse is already waiting on the
            // result queue. A long first wait would otherwise pin
            // the caller for up to BROWSE_BUDGET_MS after a cancel.
            while (!closed.get() && clock.nowRealtime() < end) {
                long remaining = end - clock.nowRealtime();
                if (remaining <= 0) break;
                long wait = Math.min(remaining, CANCEL_POLL_MS);
                Candidate c = results.poll(wait, TimeUnit.MILLISECONDS);
                if (c != null) out.add(c);
                if (cancelled) break;
            }
            // Drain any remaining results that landed before the
            // budget expired so the caller has the full list.
            Candidate c;
            while ((c = results.poll()) != null) out.add(c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Cancelled");
        } finally {
            closed.set(true);
            try { driver.stop(listener); } catch (RuntimeException ignored) { }
        }
        return out;
    }

    /**
     * Sanitize a resolved NSD service into a Candidate or null when
     * the address/protocol does not match the rules. The TXT pin is
     * NEVER trusted: a forged pin grants no elevation.
     */
    static Candidate sanitize(NsdServiceInfo service) {
        if (service == null) return null;
        try {
            java.util.Map<String, byte[]> txt = service.getAttributes();
            byte[] protocol = txt == null ? null : txt.get("protocol");
            if (protocol == null || !PROTOCOL.equals(new String(protocol, StandardCharsets.US_ASCII))) {
                return null;
            }
            // A forged TXT pin must NOT shortcut trust. We deliberately
            // do not check service.getAttribute("certpin") here: the
            // trust anchor is the observed TLS certificate, not the
            // multicast TXT record.
            if (service.getPort() <= 0 || service.getPort() > 65535) return null;
            if (!(service.getHost() instanceof Inet4Address)) return null;
            Inet4Address host = (Inet4Address) service.getHost();
            if (host.isAnyLocalAddress() || host.isLoopbackAddress()
                    || host.isMulticastAddress()) return null;
            if (!isPrivateAddress(host)) return null;
            // TXT `name` is display-only. Fall back to the service
            // name when the TXT entry is absent.
            String rawName = null;
            if (txt != null) {
                byte[] nameBytes = txt.get("name");
                if (nameBytes != null) {
                    try { rawName = new String(nameBytes, StandardCharsets.UTF_8); }
                    catch (Exception ignored) { rawName = null; }
                }
            }
            if (rawName == null || rawName.isEmpty()) {
                rawName = service.getServiceName();
            }
            String name = sanitizeName(rawName);
            return new Candidate(name, host.getHostAddress(), service.getPort());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Sanitize a display name: replace bidi-override / control /
     *  format characters with {@code _}, drop control characters,
     *  preserve normal letters and common punctuation, length-cap
     *  at {@link #MAX_NAME_LENGTH}. {@link Character#getDirectionality}
     *  is the correct API for bidi classification — {@link
     *  Character#getType} returns the Unicode general category
     *  (e.g. {@code UPPERCASE_LETTER}) and is NOT directionality. */
    static String sanitizeName(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            int directionality = Character.getDirectionality(c);
            boolean bidiOverride = directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING
                    || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING
                    || directionality == Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE
                    || directionality == Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE
                    || directionality == Character.DIRECTIONALITY_POP_DIRECTIONAL_FORMAT;
            boolean control = Character.isISOControl(c);
            int category = Character.getType(c);
            boolean format = category == Character.FORMAT
                    || category == Character.CONTROL
                    || category == Character.SURROGATE
                    || category == Character.PRIVATE_USE
                    || category == Character.UNASSIGNED;
            if (bidiOverride || control || format) {
                sb.append('_');
            } else {
                sb.append(c);
            }
            if (sb.length() >= MAX_NAME_LENGTH) break;
        }
        return sb.toString();
    }

    /** True for IPv4 RFC1918 / link-local unicast / CGNAT/Tailscale
     *  addresses. Public / unspecified / loopback / multicast / IPv6
     *  addresses are rejected. */
    static boolean isPrivateAddress(java.net.InetAddress address) {
        if (address == null) return false;
        if (address.isAnyLocalAddress()) return false;
        if (address.isLoopbackAddress()) return false;
        if (address.isMulticastAddress()) return false;
        if (address.isSiteLocalAddress()) return true;
        byte[] raw = address.getAddress();
        if (raw.length != 4) return false;
        int b0 = raw[0] & 0xff;
        int b1 = raw[1] & 0xff;
        // 10.0.0.0/8
        if (b0 == 10) return true;
        // 172.16.0.0/12
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return true;
        // 192.168.0.0/16
        if (b0 == 192 && b1 == 168) return true;
        // 169.254.0.0/16 link-local unicast
        if (b0 == 169 && b1 == 254) return true;
        // 100.64.0.0/10 CGNAT / Tailscale
        if (b0 == 100 && b1 >= 64 && b1 <= 127) return true;
        return false;
    }

    /** Typed result of {@link #parseManualEndpoint(String, int)}.
     *  Carries the validated IPv4 literal or hostname and the
     *  resolved port separately so callers never have to resplit
     *  the rendered string. */
    public static final class ParsedEndpoint {
        public final String host;
        public final int port;
        ParsedEndpoint(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    /** Parse a manually-typed PC endpoint and return the validated
     *  {@link ParsedEndpoint}, or throw {@link IllegalArgumentException}
     *  for credentials, paths, queries, fragments, brackets, IPv6
     *  literals (multiple colons), or invalid ports. The VR port
     *  defaults to {@link #DEFAULT_VR_PORT} when no port was
     *  supplied. IPv4 literals and plain RFC-952 hostnames
     *  (letters, digits, dot, hyphen) are accepted; the port is
     *  optional and must be in 1..65535 when present. */
    public static ParsedEndpoint parseManualEndpoint(String hostPort, int defaultPort) {
        if (hostPort == null) throw new IllegalArgumentException("Address is required");
        String trimmed = hostPort.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("Address is required");
        if (trimmed.length() > 256) throw new IllegalArgumentException("Address is too long");
        // URI components that must NOT appear in a bare endpoint.
        // Brackets are explicitly rejected so an IPv6 literal
        // ("[::1]:port") cannot slip past the manual-entry path.
        for (String forbidden : new String[] { "://", "@", "/", "?", "#", "[", "]" }) {
            if (trimmed.contains(forbidden)) {
                throw new IllegalArgumentException("Address must be host[:port]");
            }
        }
        // A single optional port separator. Two or more colons ⇒
        // IPv6 literal ⇒ reject.
        int colon = trimmed.indexOf(':');
        if (colon >= 0) {
            int secondColon = trimmed.indexOf(':', colon + 1);
            if (secondColon >= 0) {
                throw new IllegalArgumentException("Address must be host[:port]");
            }
        }
        String hostPart;
        int portPart;
        if (colon < 0) {
            hostPart = trimmed;
            portPart = defaultPort;
        } else {
            hostPart = trimmed.substring(0, colon);
            try { portPart = Integer.parseInt(trimmed.substring(colon + 1)); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid port");
            }
        }
        if (hostPart.isEmpty()) throw new IllegalArgumentException("Address is required");
        if (portPart < 1 || portPart > 65535) throw new IllegalArgumentException("Invalid port");
        // Host must be an IPv4 literal or a plain hostname: letters,
        // digits, dot, hyphen — NO colons, NO brackets (already
        // excluded). The pairing code re-validates this shape, but the
        // manual-entry picker surfaces the error here so the user
        // never reaches the wire with a malformed address.
        if (!hostPart.matches("[A-Za-z0-9.\\-]+")) {
            throw new IllegalArgumentException("Address must be host[:port]");
        }
        if (hostPart.startsWith("-") || hostPart.endsWith("-")) {
            throw new IllegalArgumentException("Address must be host[:port]");
        }
        return new ParsedEndpoint(hostPart, portPart);
    }

    /** Default VR port used when no port was supplied. */
    public static final int DEFAULT_VR_PORT = 28540;
}