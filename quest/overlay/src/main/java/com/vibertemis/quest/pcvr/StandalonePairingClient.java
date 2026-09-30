package com.vibertemis.quest.pcvr;

import com.limelight.nvstream.http.ComputerDetails;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.spec.*;
import java.util.LinkedHashSet;
import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.net.ssl.*;
import org.json.JSONObject;

/** First-use TLS is restricted to enrollment; human code comparison establishes
 * trust. No GameStream keys or APIs are used. All subsequent traffic is pinned. */
public final class StandalonePairingClient implements PairingSession {
    static final String DOMAIN = "VIBERTEMIS-STANDALONE-1";
    /** Maximum accepted server error body, 16 KiB. Pairs with the
     *  server-side http.MaxBytesReader cap so a hostile peer cannot
     *  exhaust our memory. */
    static final int MAX_ERROR_BODY_BYTES = 16384;
    public interface Progress { void comparing(String code); }
    private volatile boolean cancelled;
    private volatile HttpsURLConnection active;
    /** Monotonic deadline (in {@link android.os.SystemClock#elapsedRealtime}
     *  ms) for the active enrollment pass. Set when the server's
     *  {@code ttl_seconds} is honoured, BEFORE the comparison-code
     *  callback is invoked so the countdown UI never observes a
     *  zero remaining. The deadline never moves backwards and is
     *  never extended by repeated callbacks or poll responses. */
    private volatile long deadlineRealtimeMs;
    @Override public void cancel() { cancelled = true; HttpsURLConnection c=active; if(c!=null)c.disconnect(); }
    private void checkCancelled() throws IOException { if(cancelled||Thread.currentThread().isInterrupted())throw new IOException("Cancelled"); }

    /** Remaining monotonic time (ms) on the active enrollment
     *  deadline. Returns zero when no enrollment is active or when
     *  the deadline has already elapsed. */
    @Override public long remainingDeadlineMs() {
        long end = deadlineRealtimeMs;
        if (end <= 0L) return 0L;
        long remaining = end - android.os.SystemClock.elapsedRealtime();
        return remaining > 0L ? remaining : 0L;
    }

    /** Bounded, structured pairing failure. The {@link #code} is one
     *  of the stable wire codes published by the PC host manager;
     *  the {@link #message} is a fixed owner-controlled message
     *  resolved from a local whitelist, never the server's text. */
    public static final class SetupFailure extends IOException {
        private static final long serialVersionUID = 1L;
        public final String code;
        public SetupFailure(String code, String message) { super(message); this.code = code; }
        public SetupFailure(String message) { this(CODE_UNKNOWN, message); }
        public boolean isClosed() { return CODE_CLOSED.equals(code); }
        public boolean isExpired() { return CODE_EXPIRED.equals(code); }
    }

    /** Stable wire codes. The host manager sends these on every
     *  pairing error envelope; values are an enum on the wire. */
    public static final String CODE_CLOSED = "CLOSED";
    public static final String CODE_EXPIRED = "EXPIRED";
    public static final String CODE_BUSY = "BUSY";
    public static final String CODE_RATE_LIMITED = "RATE_LIMITED";
    public static final String CODE_INVALID = "INVALID";
    public static final String CODE_CAPACITY = "CAPACITY";
    public static final String CODE_STORAGE = "STORAGE_FAILED";
    /** Local code surfaced when the PC returned a "denied" poll
     *  state. Distinct from {@link #CODE_CLOSED} because the user
     *  simply needs to retry from the headset — they do NOT need
     *  to flip a "Setup VR" mode on the PC again. */
    public static final String CODE_DENIED = "DENIED";
    public static final String CODE_UNKNOWN = "UNKNOWN";

    /** Whitelist of owner-controlled, neutral messages. The server
     *  text is NEVER surfaced directly; missing-code responses get
     *  a first-time-neutral fallback that does NOT assume the user
     *  was previously paired. */
    public static String messageForCode(String code) {
        if (CODE_CLOSED.equals(code)) {
            return "Pairing is closed on the PC. In Windows VR Host Manager, choose Setup VR to enable pairing, then retry here.";
        }
        if (CODE_EXPIRED.equals(code)) {
            return "Pairing request expired. Retry here.";
        }
        if (CODE_BUSY.equals(code)) {
            return "Another headset is awaiting approval on the PC. Check the request there, then retry.";
        }
        if (CODE_RATE_LIMITED.equals(code)) {
            return "Too many pairing requests. Wait about two minutes, then retry here.";
        }
        if (CODE_INVALID.equals(code)) {
            return "Pairing identity did not match. Cancel and retry on both devices.";
        }
        if (CODE_CAPACITY.equals(code)) {
            return "PC paired-headset limit reached. In the PC VR Host Manager, choose Forget paired headsets, then retry here.";
        }
        if (CODE_STORAGE.equals(code)) {
            return "PC could not save the pairing. Retry; reinstall VR Manager if the error persists.";
        }
        if ("DENIED".equals(code)) {
            return "The PC declined this pairing request. Retry here.";
        }
        return "Pairing could not start. In Windows VR Host Manager, choose Setup VR to enable pairing, then retry here.";
    }

    /** Parse the bounded server error envelope as a flat
     *  {@code {"error": "<owner text>", "code": "<CODE>"}}; legacy
     *  preview8 servers send {@code {"error": "<text>"}} only. The
     *  body is already capped at 16 KiB by {@link #readBoundedError}
     *  and this parser additionally enforces:
     *  <ul>
     *    <li>top-level object only (no nested arrays/objects);</li>
     *    <li>string fields only — duplicate keys, non-string values,
     *        numeric booleans or trailing tokens are rejected;</li>
     *    <li>bounded {@code code} whitelist — anything outside the
     *        known enum is treated as UNKNOWN.</li>
     *  </ul>
     *  The server's {@code error} text is read for code extraction
     *  and then DISCARDED — only the bounded local message is
     *  surfaced to the user. A malformed or nested payload (deep
     *  enough to bypass the depth=1 limit, or oversized) returns
     *  UNKNOWN with the first-time-neutral fallback. */
    static SetupFailure errorFromBody(byte[] body) {
        if (body == null || body.length == 0 || body.length > MAX_ERROR_BODY_BYTES) {
            return new SetupFailure(CODE_UNKNOWN, messageForCode(CODE_UNKNOWN));
        }
        String json = new String(body, StandardCharsets.UTF_8);
        String code = parseFlatEnvelopeCode(json);
        return new SetupFailure(code, messageForCode(code));
    }

    /** Parse only the {@code code} field from a flat envelope.
     *  Returns {@link #CODE_UNKNOWN} on any parse failure or
     *  unknown / missing / malformed code value.
     *
     *  <p><b>Duplicate-key detection:</b> every key (known or
     *  unknown) is added to a bounded set; a repeated key
     *  rejects the entire envelope. The cap
     *  ({@link #MAX_ERROR_KEYS}) is small enough that any
     *  well-formed pairing envelope fits but tight enough that a
     *  hostile peer cannot inflate the set inside the 16 KiB
     *  body budget. */
    static final int MAX_ERROR_KEYS = 32;

    private static String parseFlatEnvelopeCode(String json) {
        if (json == null || json.isEmpty()) return CODE_UNKNOWN;
        try {
            android.util.JsonReader r = new android.util.JsonReader(new java.io.StringReader(json));
            try {
                r.setLenient(false);
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) return CODE_UNKNOWN;
                r.beginObject();
                String code = CODE_UNKNOWN;
                boolean sawCode = false;
                java.util.HashSet<String> seenKeys = new java.util.HashSet<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (!seen.add(key) || seen.size() > 32) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    // Duplicate ANY key (known or unknown) is a
                    // tampering signal. Reject the envelope.
                    if (!seenKeys.add(key)) return CODE_UNKNOWN;
                    // Bound the set so a hostile peer can't grow
                    // it beyond the documented envelope shape.
                    if (seenKeys.size() > MAX_ERROR_KEYS) return CODE_UNKNOWN;
                    if ("code".equals(key)) {
                        sawCode = true;
                        if (r.peek() != android.util.JsonToken.STRING) return CODE_UNKNOWN;
                        code = r.nextString();
                    } else if ("error".equals(key)) {
                        if (r.peek() != android.util.JsonToken.STRING) return CODE_UNKNOWN;
                        r.nextString(); // value discarded
                    } else {
                        // Any other flat scalar field (string,
                        // number, boolean, or null) is silently
                        // accepted because unknown future additions
                        // to the wire envelope must NOT turn an
                        // otherwise valid BUSY / RATE_LIMITED message
                        // into an UNKNOWN fallback. Nested
                        // objects/arrays at depth 1 are still
                        // rejected so a hostile peer cannot smuggle
                        // in a deeply nested shape. NULL is allowed
                        // for unknown scalars only — for known
                        // fields (`code`, `error`) only STRING is
                        // accepted, see the explicit branches above.
                        android.util.JsonToken t = r.peek();
                        if (t != android.util.JsonToken.STRING
                                && t != android.util.JsonToken.NUMBER
                                && t != android.util.JsonToken.BOOLEAN
                                && t != android.util.JsonToken.NULL) {
                            return CODE_UNKNOWN;
                        }
                        r.skipValue();
                    }
                }
                r.endObject();
                if (r.peek() != android.util.JsonToken.END_DOCUMENT) return CODE_UNKNOWN;
                if (!sawCode) return CODE_UNKNOWN;
                return isKnownCode(code) ? code : CODE_UNKNOWN;
            } finally {
                try { r.close(); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) {
            return CODE_UNKNOWN;
        }
    }

    /** Bounded whitelist of accepted wire codes. Anything outside
     *  this list is mapped to UNKNOWN so the UI only ever shows a
     *  known message. */
    private static boolean isKnownCode(String code) {
        return CODE_CLOSED.equals(code) || CODE_EXPIRED.equals(code)
                || CODE_BUSY.equals(code) || CODE_RATE_LIMITED.equals(code)
                || CODE_INVALID.equals(code) || CODE_CAPACITY.equals(code)
                || CODE_STORAGE.equals(code) || CODE_DENIED.equals(code);
    }

    /** Compatibility overload kept for callers that still hold a
     *  Moonlight ComputerDetails (Screen gaming path). The setup UX
     *  uses {@link #enroll(VrSetupDiscovery.Candidate, Progress)}
     *  or {@link #enroll(String, int, Progress)} instead. The
     *  ComputerDetails address tuples are mapped to the VR port
     *  28540 because GameStream ports (47989 etc.) are NOT a VR
     *  endpoint. If the first address fails with a transport error
     *  (connect refused / read timeout) the next tuple is tried; a
     *  security or pairing-rejection error stops the fallback
     *  immediately so a rejection is not hidden by trying another PC. */
    public HostPairing enroll(ComputerDetails pc, Progress progress) throws Exception {
        LinkedHashSet<String> addresses=new LinkedHashSet<>();
        for(ComputerDetails.AddressTuple a:new ComputerDetails.AddressTuple[]{pc.activeAddress,pc.manualAddress,pc.localAddress,pc.remoteAddress,pc.ipv6Address})
            if(a!=null)addresses.add(a.address);
        if (addresses.isEmpty()) {
            throw new SetupFailure("Could not reach VR Host Manager. Start hosting on the PC and choose Pair headset. Away from home, connect a VPN to home; Moonlight ports alone are not enough.");
        }
        for (String address : addresses) {
            try {
                return enrollInternal(address, VrSetupDiscovery.DEFAULT_VR_PORT, progress);
            } catch (SetupFailure e) {
        // Preserve the first setup response or identity failure; do not silently
        // try another PC. Each permitted transport retry creates a fresh nonce.
                throw e;
            } catch (java.io.IOException io) {
                // Genuine transport failure on one address: try the
                // next. Cancellation aborts the loop instead of
                // burning through every remaining address.
                if (cancelled || Thread.currentThread().isInterrupted()) throw io;
            }
        }
        throw new SetupFailure("Could not reach VR Host Manager. Start hosting on the PC and choose Pair headset. Away from home, connect a VPN to home; Moonlight ports alone are not enough.");
    }

    /** New setup-UX overload: enroll against a validated mDNS
     *  candidate. The advertised port is honoured on every request;
     *  trust is established through the observed TLS certificate,
     *  not the multicast TXT pin. */
    public HostPairing enroll(VrSetupDiscovery.Candidate candidate, Progress progress) throws Exception {
        if (candidate == null) throw new SetupFailure("Pairing address is required.");
        return enrollInternal(candidate.address, candidate.port, progress);
    }

    /** Setup-UX overload for a manually-typed endpoint. The caller is
     *  responsible for validating the host/port shape. */
    @Override public HostPairing enroll(String host, int port, Progress progress) throws Exception {
        if (host == null || host.isEmpty()) throw new SetupFailure("Pairing address is required.");
        if (port < 1 || port > 65535) throw new SetupFailure("Invalid VR port.");
        return enrollInternal(host, port, progress);
    }

    /** Shared enroll driver. The progress callback receives the
     *  comparison code EXACTLY once and is then never re-issued.
     *  The monotonic deadline is set BEFORE the callback so the
     *  countdown UI never observes a zero remaining on the first
     *  tick. The deadline never moves backwards and is never
     *  extended by repeated callbacks or poll responses.
     *
     *  <p><b>Failure classification.</b> Three explicit phases:
     *  <ol>
     *    <li><b>Initial transport.</b> {@link SetupFailure} from
     *        the server (404/429/error envelope) is preserved as-is
     *        — the outer fallback stops on it. Genuine network
     *        errors ({@link java.net.ConnectException},
     *        {@link java.net.SocketTimeoutException},
     *        {@link java.net.UnknownHostException},
     *        {@link java.net.NoRouteToHostException}) are the ONLY
     *        {@link IOException}s allowed to propagate so the outer
     *        fallback in {@link #enroll(ComputerDetails, Progress)}
     *        tries the next address. Every other {@link IOException}
     *        (SSL handshake failure, body read error, generic
     *        socket / SSL transport) is a protocol / security event
     *        and maps to {@link #CODE_UNKNOWN} with an honest
     *        connection-guidance message BEFORE the outer fallback
     *        sees it, preserving the first setup or identity failure.</li>
     *    <li><b>After a valid challenge.</b> Every failure is a
     *        server-side decision. {@link SetupFailure} from the
     *        server (state=denied, code=CLOSED/EXPIRED/DENIED/
     *        RATE_LIMITED/INVALID, malformed poll body, OAEP
     *        decryption failure, malformed / missing certificate)
     *        propagates as-is. The generic catch also maps
     *        every non-IO {@link Exception} (cert / crypto /
     *        parser failure) to {@link #CODE_INVALID} AFTER
     *        {@code checkCancelled()} — cert / crypto failures
     *        must NEVER be wrapped as a transport-style IOException
     *        that the outer fallback could retry on a different
     *        address. A genuine transport IOException during the
     *        poll loop is surfaced as a SetupFailure with a
     *        connection-error message, NOT identity INVALID — the
     *        user already paired, the PC just went away
     *        mid-approval. Outer fallback MUST NOT retry to a
     *        different address once a valid challenge has been
     *        observed.</li>
     *    <li><b>Cancellation.</b> {@link #cancel()} flips the
     *        {@link #cancelled} flag; subsequent {@code checkCancelled()}
     *        throws {@code IOException("Cancelled")} which the
     *        outer fallback detects and stops on. An
     *        {@link InterruptedException} (e.g. the {@code
     *        Thread.sleep(1000)} in the poll loop) restores the
     *        interrupt flag and is surfaced as a cancellation
     *        SetupFailure — NEVER misclassified as a transport
     *        error.</li>
     *  </ol> */
    private HostPairing enrollInternal(String host, int port, Progress progress) throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);
        KeyPair keys=generator.generateKeyPair();
        byte[] nonceBytes=new byte[32];new SecureRandom().nextBytes(nonceBytes);
        String nonce=HostPairing.hex(nonceBytes);
        String key=android.util.Base64.encodeToString(keys.getPublic().getEncoded(),android.util.Base64.NO_WRAP);
        String keyHash=hash(keys.getPublic().getEncoded());
        URL base=null;ParsedChallenge challenge=null;String pin=null;String pem=null;
        String id=null,serverNonce=null;
        long deadlineMs=0L;
        boolean completed=false;
        // Preserve the first setup response or identity failure; do not silently
        // try another PC. Each permitted transport retry creates a fresh nonce.
        boolean gotValidChallenge=false;
        try {
            URL candidate=new URL("https",host,port,"/pairing/begin");
            checkCancelled();
            // STEP 1: initial /pairing/begin request. Server-decided
            // SetupFailure codes (CLOSED, EXPIRED, DENIED,
            // RATE_LIMITED, INVALID, BUSY, CAPACITY, STORAGE) are
            // preserved. Genuine network-level IOException types
            // propagate so the outer fallback retries the next
            // address. Everything else (SSL handshake, body read,
            // parser) is a protocol/security event and maps to
            // CODE_INVALID BEFORE the outer fallback sees it.
            Reply reply;
            try {
                reply=request(candidate,null,new JSONObject().put("schema",1).put("client_key",key).put("client_nonce",nonce),false);
            } catch (SetupFailure sf) {
                throw sf;
            } catch (java.net.ConnectException ce) {
                throw ce;
            } catch (java.net.SocketTimeoutException ste) {
                throw ste;
            } catch (java.net.UnknownHostException uhe) {
                throw uhe;
            } catch (java.net.NoRouteToHostException nrthe) {
                throw nrthe;
            } catch (java.io.IOException ioe) {
        // Preserve the first setup response or identity failure; do not silently
        // try another PC. Each permitted transport retry creates a fresh nonce.
                checkCancelled();
                throw new SetupFailure(CODE_UNKNOWN,
                        "Could not connect to PC VR Host Manager at " + host + ":" + port
                                + ". Start hosting on the PC and choose Pair headset."
                                + " Away from home, connect a VPN to home;"
                                + " Moonlight ports alone are not enough.");
            }
            // STEP 2: parse the challenge and authenticate the
            // certificate. parseChallenge has already thrown
            // SetupFailure(CODE_INVALID) for any malformed body.
            // The remaining check is the cert pin.
            ParsedChallenge c=reply.challenge;
            X509Certificate certificate=parseCertificate(c.certPem);
            if(!reply.pin.equals(hash(certificate.getEncoded()))) {
                throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
            }
            pin=reply.pin;pem=c.certPem;base=candidate;challenge=c;
            id=c.sessionId;serverNonce=c.serverNonce;
            // Set the monotonic deadline BEFORE invoking the
            // comparison-code callback. The UI ticks
            // remainingDeadlineMs() from this anchor.
            long ttlMs = c.ttlSeconds * 1000L;
            deadlineMs=android.os.SystemClock.elapsedRealtime()+ttlMs;
            deadlineRealtimeMs = deadlineMs;
            progress.comparing(comparisonCode(pin,keyHash,nonce,serverNonce,id));
            gotValidChallenge=true;
            // STEP 3: poll loop. The server-decided SetupFailure
            // codes (state=denied, 4xx error envelopes with
            // CLOSED/EXPIRED/RATE_LIMITED/etc.) are preserved.
            // Genuine transport IOException during the poll is
            // surfaced as a SetupFailure with a connection-error
            // message — NOT identity INVALID.
            JSONObject poll=proof(keys.getPrivate(),"POLL",id,nonce,serverNonce,pin);
            while(android.os.SystemClock.elapsedRealtime()<deadlineMs) {
                checkCancelled();
                Reply pollReply;
                try {
                    pollReply=request(new URL(base,"/pairing/poll"),pin,poll,false);
                } catch (SetupFailure sf) {
                    throw sf;
                } catch (java.io.IOException ioe) {
                    // Mid-poll transport failure: PC went away
                    // after we already authenticated. Surface as a
                    // connection error, not identity INVALID. Use
                    // a SetupFailure with a specific message so the
                    // Hub can render it directly; the code is
                    // CODE_UNKNOWN so it doesn't accidentally match
                    // the existing retryable whitelist (the Hub
                    // shows the message verbatim either way).
                    throw new SetupFailure("Lost connection to your PC during approval. Retry pairing.");
                }
                ParsedPoll result=pollReply.poll;
                String state=result.state;
                if(state.equals("denied"))throw new SetupFailure(CODE_DENIED, messageForCode(CODE_DENIED));
                if(state.equals("approved")) {
                    String device=result.deviceId;
                    // Approved state MUST carry a well-formed
                    // device_id and encrypted_token. Malformed
                    // values map to the local INVALID code (rather
                    // than to opaque ad-hoc messages) so the user
                    // sees a single whitelisted recovery message
                    // and retry behaviour is consistent with every
                    // other INVALID cause.
                    if(device==null || !hex(device,32))throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    String tokenEnc=result.encryptedToken;
                    if(tokenEnc==null || tokenEnc.isEmpty())throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    // Base64 decoder IllegalArgumentException on
                    // hostile ciphertext also maps to CODE_INVALID.
                    byte[] encrypted;
                    try {
                        encrypted=android.util.Base64.decode(tokenEnc,android.util.Base64.NO_WRAP);
                    } catch (IllegalArgumentException iae) {
                        throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    }
                    if(encrypted.length!=256)throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    // OAEP BadPaddingException / IllegalBlockSizeException
                    // (wrong label, broken RSA-OAEP, hostile payload)
                    // also map to CODE_INVALID. The user must see
                    // the whitelisted INVALID recovery message and
                    // not a misleading connection-error message.
                    byte[] plaintext;
                    String token;
                    try {
                        Cipher cipher=Cipher.getInstance("RSA/ECB/OAEPPadding");
                        cipher.init(Cipher.DECRYPT_MODE,keys.getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,
                            new PSource.PSpecified((DOMAIN+"-TOKEN\n1\n"+id).getBytes(StandardCharsets.UTF_8))));
                        plaintext=cipher.doFinal(encrypted);
                        token=new String(plaintext,StandardCharsets.US_ASCII);
                        java.util.Arrays.fill(plaintext,(byte)0);
                    } catch (Exception ee) {
                        throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    }
                    if(!hex(token,64))throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    HostPairing paired=HostPairing.parse(new JSONObject().put("host_address",new ComputerDetails.AddressTuple(base.getHost(),port).toString())
                        .put("certpin",pin).put("cert_pem",pem).put("token",token).put("device_id",device).put("pairing_kind","standalone").toString());
                    checkCancelled();completed=true;return paired;
                }
                if(!state.equals("pending"))throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                Thread.sleep(1000);
            }
            throw new SetupFailure(CODE_EXPIRED, messageForCode(CODE_EXPIRED));
        } catch(SetupFailure e){throw e;}
        catch(InterruptedException ie){
            // Restore the interrupt flag and terminate. Do NOT
            // advance to the next address — an interrupt is a
            // hard cancel from the caller, not a transport
            // signal. Surface as a cancellation SetupFailure so
            // the outer enroll(...) overloads propagate it
            // unchanged.
            Thread.currentThread().interrupt();
            throw new SetupFailure("Cancelled");
        }
        catch(java.io.IOException ioe){
            // IO catches checkCancelled first so a cancelled
            // request is never misclassified as a transport
            // error that the outer fallback might retry.
            checkCancelled();
        // Preserve the first setup response or identity failure; do not silently
        // try another PC. Each permitted transport retry creates a fresh nonce.
            if (!gotValidChallenge && (ioe instanceof java.net.ConnectException
                    || ioe instanceof java.net.SocketTimeoutException
                    || ioe instanceof java.net.UnknownHostException
                    || ioe instanceof java.net.NoRouteToHostException)) {
                throw ioe;
            }
            // Every other IOException (generic socket / SSL
            // transport, EOF, body read error) is a protocol /
            // security event. Surface CODE_UNKNOWN with honest
            // connection guidance and NO fallback so the outer
            // loop stops and the user sees a meaningful message.
            throw new SetupFailure(CODE_UNKNOWN,
                    "Could not connect to PC VR Host Manager at " + host + ":" + port
                            + ". Start hosting on the PC and choose Pair headset."
                            + " Away from home, connect a VPN to home;"
                            + " Moonlight ports alone are not enough.");
        }
        catch(Exception e){
            // ALL non-IO failures after checkCancelled become
            // SetupFailure INVALID. Cert / crypto / parser
            // failures stay INVALID; nothing here is a
            // transport-level event the outer fallback should
            // retry on a different address. The previous
            // behaviour wrapped these as IOException with a
            // misleading "Could not reach VR Host Manager"
            // connection-error message — that path was the bug
            // the bounded review caught: a malformed cert PEM
            // or BadPaddingException used to silently advance
            // the outer fallback loop to the next address.
            checkCancelled();
            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
        }
        finally {
            if(!completed&&challenge!=null&&base!=null&&pin!=null) {
                try {requestBody(new URL(base,"/pairing/cancel"),pin,proof(keys.getPrivate(),"CANCEL",challenge.sessionId,nonce,challenge.serverNonce,pin).toString().getBytes(StandardCharsets.UTF_8),true,false);}catch(Exception ignored){}
            }
            // Providers may not support destroy. Keys are never persisted or logged.
            try {keys.getPrivate().destroy();}catch(Exception ignored){}
            deadlineRealtimeMs = 0L;
        }
    }
    static String hash(byte[] data) throws Exception {return HostPairing.hex(MessageDigest.getInstance("SHA-256").digest(data));}
    static boolean hex(String value,int count){return value.matches("[0-9a-f]{"+count+"}");}
    static String comparisonCode(String pin,String key,String client,String server,String id) throws Exception {
        String h=hash((DOMAIN+"-CODE\n1\n"+pin+"\n"+key+"\n"+client+"\n"+server+"\n"+id).getBytes(StandardCharsets.UTF_8)).substring(0,16).toUpperCase(java.util.Locale.ROOT);
        return h.substring(0,4)+"-"+h.substring(4,8)+"-"+h.substring(8,12)+"-"+h.substring(12);
    }
    static byte[] transcript(String action,String id,String nonce,String server,String pin) {
        return (DOMAIN+"-"+action+"\n1\n"+id+"\n"+nonce+"\n"+server+"\n"+pin).getBytes(StandardCharsets.UTF_8);
    }
    private static JSONObject proof(PrivateKey key,String action,String id,String nonce,String server,String pin) throws Exception {
        Signature signature;
        try{signature=Signature.getInstance("RSASSA-PSS");}catch(NoSuchAlgorithmException e){signature=Signature.getInstance("SHA256withRSA/PSS");}
        signature.setParameter(new PSSParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA256,32,1));
        signature.initSign(key);signature.update(transcript(action,id,nonce,server,pin));
        return new JSONObject().put("schema",1).put("session_id",id).put("signature",android.util.Base64.encodeToString(signature.sign(),android.util.Base64.NO_WRAP));
    }
    private static X509Certificate parseCertificate(String pem) throws Exception {
        X509Certificate c=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
        c.checkValidity();return c;
    }
    /** Maximum accepted 200 body. Pairs with the
     *  server-side http.MaxBytesReader cap so a hostile peer cannot
     *  exhaust our memory. The success-body parser additionally
     *  enforces a strict flat-only / depth=1 schema. */
    static final int MAX_SUCCESS_BODY_BYTES = 16384;

    /** Strict scalar field contract for the success body. Only the
     *  documented flat string / numeric fields are accepted; any
     *  nested object / array at depth=1, any duplicate known key, or
     *  any non-document trailing input is rejected. This is the
     *  enrollment-time equivalent of the bounded error-envelope
     *  parser: an untrusted peer must NOT be allowed to wedge the
     *  headset into a half-decoded state. */
    static final class ParsedChallenge {
        final String sessionId;
        final String serverNonce;
        final String certPem;
        final long ttlSeconds;
        final long expiresUnix;
        ParsedChallenge(String id, String nonce, String pem, long ttl, long expires) {
            this.sessionId = id;
            this.serverNonce = nonce;
            this.certPem = pem;
            this.ttlSeconds = ttl;
            this.expiresUnix = expires;
        }
    }

    static final class ParsedPoll {
        final int schema;
        final String state;
        final String deviceId;
        final String encryptedToken;
        ParsedPoll(int schema, String state, String deviceId, String encryptedToken) {
            this.schema = schema;
            this.state = state;
            this.deviceId = deviceId;
            this.encryptedToken = encryptedToken;
        }
    }

    /** Parse the bounded flat success body for /pairing/begin. The
     *  body is already capped at {@link #MAX_SUCCESS_BODY_BYTES}; the
     *  parser additionally enforces:
     *  <ul>
     *    <li>top-level object only (no nested arrays/objects);</li>
     *    <li>scalar values only — duplicate known keys, nested
     *        values, or trailing tokens are rejected;</li>
     *    <li>bound {@code schema} (== 1) and {@code ttl_seconds}
     *        range (1..180); {@code expires_unix} must be positive.</li>
     *  </ul>
     *  The parser uses {@link android.util.JsonReader} in
     *  non-lenient mode so a stray comma or unquoted key is a parse
     *  error. Parse errors throw {@link SetupFailure} with
     *  {@link #CODE_INVALID} so the outer fallback loop never
     *  retries the wrong address with a half-decoded challenge —
     *  a SetupFailure stops fallback immediately, a generic
     *  IOException (transport) would have advanced to the next
     *  address and obscured the original failure. */
    static ParsedChallenge parseChallenge(byte[] body) throws SetupFailure {
        if (body == null || body.length == 0 || body.length > MAX_SUCCESS_BODY_BYTES) {
            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
        }
        String json = new String(body, StandardCharsets.UTF_8);
        try {
            android.util.JsonReader r = new android.util.JsonReader(new java.io.StringReader(json));
            try {
                r.setLenient(false);
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                r.beginObject();
                Integer schema = null;
                String id = null, nonce = null, pem = null;
                Long ttl = null, expires = null;
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (!seen.add(key) || seen.size() > 32) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    if ("schema".equals(key)) {
                        if (schema != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.NUMBER) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        schema = r.nextInt();
                    } else if ("session_id".equals(key)) {
                        if (id != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        id = r.nextString();
                    } else if ("server_nonce".equals(key)) {
                        if (nonce != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        nonce = r.nextString();
                    } else if ("cert_pem".equals(key)) {
                        if (pem != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        pem = r.nextString();
                    } else if ("ttl_seconds".equals(key)) {
                        if (ttl != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.NUMBER) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        ttl = (long) r.nextInt();
                    } else if ("expires_unix".equals(key)) {
                        if (expires != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.NUMBER) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        expires = r.nextLong();
                    } else {
                        // Unknown flat fields are silently ignored
                        // to keep the contract open to future
                        // additions; nested values are rejected.
                        // BOOLEAN and NULL are accepted here so a
                        // future addition of, e.g., a feature
                        // flag or absent marker does not turn an
                        // otherwise valid challenge into INVALID.
                        // Known fields (schema, session_id,
                        // server_nonce, cert_pem, ttl_seconds,
                        // expires_unix) are strict above and never
                        // reach this branch — they each reject
                        // BOOLEAN and NULL at their own
                        // explicit-typed check.
                        if (r.peek() != android.util.JsonToken.STRING
                                && r.peek() != android.util.JsonToken.NUMBER
                                && r.peek() != android.util.JsonToken.BOOLEAN
                                && r.peek() != android.util.JsonToken.NULL) {
                            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        }
                        r.skipValue();
                    }
                }
                r.endObject();
                if (r.peek() != android.util.JsonToken.END_DOCUMENT) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                if (schema == null || schema != 1) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                if (id == null || nonce == null || pem == null) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                if (!hex(id, 64) || !hex(nonce, 64)) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                // Both ttl_seconds AND expires_unix missing: refuse
                // to grant a default lifetime. A hostile peer that
                // omits both must not be able to choose an arbitrary
                // deadline; the server is required to advertise one.
                if (ttl == null && (expires == null || expires <= 0L)) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                long resolvedTtl;
                if (ttl != null) {
                    // ttl_seconds is canonical for new servers. It
                    // is bounded 1..180 and is independent of the
                    // host's wall clock, so clock-skew on the
                    // headset cannot extend or shorten the deadline.
                    if (ttl < 1L || ttl > 180L) {
                        throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    }
                    resolvedTtl = ttl;
                } else {
                    // Legacy preview8 fallback: derive TTL from
                    // expires_unix. If the expiry has already
                    // elapsed, reject the challenge as expired
                    // rather than silently granting 1..180 seconds.
                    long nowUnix = System.currentTimeMillis() / 1000L;
                    long delta = expires - nowUnix;
                    if (delta <= 0L) {
                        throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    }
                    resolvedTtl = Math.min(180L, Math.max(1L, delta));
                }
                long resolvedExpires = expires != null ? expires : 0L;
                return new ParsedChallenge(id, nonce, pem, resolvedTtl, resolvedExpires);
            } finally { try { r.close(); } catch (Exception ignored) { } }
        } catch (SetupFailure e) { throw e; }
        catch (Exception e) {
            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
        }
    }

    /** Parse the bounded flat success body for /pairing/poll. The
     *  body is already capped at {@link #MAX_SUCCESS_BODY_BYTES}; the
     *  parser enforces the same flat-only schema as the challenge
     *  parser. The encrypted_token is preserved EXACTLY so the
     *  OAEP/RSA-PKCS transcript match stays byte-exact. Parse
     *  errors throw {@link SetupFailure} with {@link #CODE_INVALID}
     *  so the post-challenge classification is consistent. */
    static ParsedPoll parsePoll(byte[] body) throws SetupFailure {
        if (body == null || body.length == 0 || body.length > MAX_SUCCESS_BODY_BYTES) {
            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
        }
        String json = new String(body, StandardCharsets.UTF_8);
        try {
            android.util.JsonReader r = new android.util.JsonReader(new java.io.StringReader(json));
            try {
                r.setLenient(false);
                if (r.peek() != android.util.JsonToken.BEGIN_OBJECT) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                r.beginObject();
                Integer schema = null;
                String state = null, device = null, encrypted = null;
                java.util.Set<String> seen = new java.util.HashSet<>();
                while (r.hasNext()) {
                    String key = r.nextName();
                    if (!seen.add(key) || seen.size() > 32) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                    if ("schema".equals(key)) {
                        if (schema != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.NUMBER) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        schema = r.nextInt();
                    } else if ("state".equals(key)) {
                        if (state != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        state = r.nextString();
                    } else if ("device_id".equals(key)) {
                        if (device != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        device = r.nextString();
                    } else if ("encrypted_token".equals(key)) {
                        if (encrypted != null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        if (r.peek() != android.util.JsonToken.STRING) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        encrypted = r.nextString();
                    } else {
                        // Unknown flat fields are silently ignored
                        // to keep the contract open to future
                        // additions; nested values are rejected.
                        // BOOLEAN and NULL are accepted here so a
                        // future addition of, e.g., a feature
                        // flag or absent marker does not turn an
                        // otherwise valid poll into INVALID.
                        // Known fields (schema, state, device_id,
                        // encrypted_token) are strict above and
                        // never reach this branch — they each
                        // reject BOOLEAN and NULL at their own
                        // explicit-typed check.
                        if (r.peek() != android.util.JsonToken.STRING
                                && r.peek() != android.util.JsonToken.NUMBER
                                && r.peek() != android.util.JsonToken.BOOLEAN
                                && r.peek() != android.util.JsonToken.NULL) {
                            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                        }
                        r.skipValue();
                    }
                }
                r.endObject();
                if (r.peek() != android.util.JsonToken.END_DOCUMENT) {
                    throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                }
                if (schema == null || schema != 1) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                if (state == null) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
                return new ParsedPoll(schema, state, device, encrypted);
            } finally { try { r.close(); } catch (Exception ignored) { } }
        } catch (SetupFailure e) { throw e; }
        catch (Exception e) {
            throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
        }
    }

    /** Reply carries the structured fields plus the observed TLS pin.
     *  The {@link #pin} is the SHA-256 of the leaf certificate the
     *  server presented on the wire; the trust anchor is this
     *  observed value, NOT the TXT pin from mDNS. */
    private static final class Reply {
        final ParsedChallenge challenge;
        final ParsedPoll poll;
        final String pin;
        Reply(ParsedChallenge c, String p) { challenge = c; poll = null; pin = p; }
        Reply(ParsedPoll p, String pin, boolean poll) { challenge = null; this.poll = p; this.pin = pin; }
    }
    private Reply request(URL url,String pin,JSONObject body,boolean cleanup) throws Exception {
        // Choose poll/challenge parsing based on the path; the cancel
        // path returns a small status object that the bounded reader
        // ignores, so a slightly stricter challenge parse is fine.
        boolean poll = "/pairing/poll".equals(url.getPath());
        return requestBody(url, pin, body.toString().getBytes(StandardCharsets.UTF_8), cleanup, poll);
    }
    /** Single pinned TLS request helper. Returns a bounded raw
     *  {@link Reply} whose body is parsed by the matching
     *  challenge / poll parser. Best-effort cancel routes through
     *  the same helper with {@code cleanup=true}. */
    private Reply requestBody(URL url, String pin, byte[] bytes, boolean cleanup, boolean poll) throws Exception {
        if(!cleanup)checkCancelled();
        final String[] observed={null};
        final java.util.concurrent.atomic.AtomicBoolean identityRejected = new java.util.concurrent.atomic.AtomicBoolean();
        X509TrustManager trust=new X509TrustManager(){
            public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}
            public void checkClientTrusted(X509Certificate[] c,String a)throws CertificateException{throw new CertificateException("Unsupported");}
            public void checkServerTrusted(X509Certificate[] c,String a)throws CertificateException{
                try{
                    if(c==null||c.length==0)throw new CertificateException("Missing identity");c[0].checkValidity();String actual=hash(c[0].getEncoded());
                    if(pin!=null&&!pin.equals(actual))throw new CertificateException("PC identity changed");observed[0]=actual;
                }catch(CertificateException e){identityRejected.set(true);throw e;}catch(Exception e){identityRejected.set(true);throw new CertificateException(e);}
            }
        };
        SSLContext tls=SSLContext.getInstance("TLS");tls.init(null,new TrustManager[]{trust},null);
        HttpsURLConnection c=(HttpsURLConnection)url.openConnection();if(!cleanup)active=c;
        try{
            if(!cleanup)checkCancelled();
            c.setSSLSocketFactory(tls.getSocketFactory());
            c.setHostnameVerifier((host,session)->{
                try{return host.equalsIgnoreCase(url.getHost())&&observed[0]!=null&&observed[0].equals(hash(session.getPeerCertificates()[0].getEncoded()));}catch(Exception e){return false;}
            });
            c.setInstanceFollowRedirects(false);c.setUseCaches(false);c.setConnectTimeout(cleanup?1000:3000);c.setReadTimeout(cleanup?1000:5000);
            c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
            c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()){out.write(bytes);}
            int status=c.getResponseCode();
            if(status==404)throw new SetupFailure("Update VR Host Manager on the PC, then choose Pair headset. Vibeshine does not need updating.");
            if(status==429)throw new SetupFailure(CODE_RATE_LIMITED, messageForCode(CODE_RATE_LIMITED));
            if(status!=200){
                byte[] errBody=readBoundedError(c);
                throw errorFromBody(errBody);
            }
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buffer=new byte[2048];int n;while((n=in.read(buffer))!=-1){if(!cleanup)checkCancelled();if(out.size()+n>MAX_SUCCESS_BODY_BYTES)throw new IOException("Pairing response too large");out.write(buffer,0,n);}
                if(observed[0]==null)throw new IOException("Missing PC identity");
                if (poll) {
                    return new Reply(parsePoll(out.toByteArray()), observed[0], true);
                }
                return new Reply(parseChallenge(out.toByteArray()), observed[0]);
            }
        } catch (IOException e) {
            if (identityRejected.get()) throw new SetupFailure(CODE_INVALID, messageForCode(CODE_INVALID));
            throw e;
        } finally {if(!cleanup)active=null;c.disconnect();}
    }
    /** Read the bounded error body for non-200 responses. Caps at
     *  {@link #MAX_ERROR_BODY_BYTES}; if the body is oversize the
     *  entire response is treated as unknown (caller maps to UNKNOWN)
     *  rather than feeding a truncated prefix to the parser. Returns
     *  an empty array when no error stream is present. */
    private static byte[] readBoundedError(HttpsURLConnection c) throws IOException {
        InputStream es = c.getErrorStream();
        if (es == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int n;
        boolean oversize = false;
        try (InputStream in = es) {
            while ((n = in.read(buffer)) != -1) {
                if (out.size() + n > MAX_ERROR_BODY_BYTES) {
                    oversize = true;
                    break;
                }
                out.write(buffer, 0, n);
            }
        } finally {
            try { es.close(); } catch (IOException ignored) { }
        }
        return oversize ? new byte[0] : out.toByteArray();
    }
}
