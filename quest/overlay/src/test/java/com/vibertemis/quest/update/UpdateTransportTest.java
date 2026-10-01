package com.vibertemis.quest.update;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Offline coverage for {@link UpdateTransport}'s release selection.
 *
 * <p>Every response is served by a fake {@link HttpURLConnection}
 * handed in through the package-private test constructor, so no test
 * here can reach the network. Trust is not stubbed: each test signs a
 * real 3072-bit RSA fixture manifest with
 * {@link UpdateTestFixture}'s key and lets
 * {@link UpdateManifest#verify} do the actual cryptography, so a test
 * that passes genuinely exercised signature, protocol, channel and
 * asset-location validation.
 *
 * <p>The fake also records the exact address of every request, which
 * is what makes "never fetched the broken older release" an assertion
 * rather than a hope. A URL with no registered reply fails the request,
 * so an unwanted fetch surfaces as a test failure instead of passing
 * unnoticed.
 */
@RunWith(RobolectricTestRunner.class)
public class UpdateTransportTest {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/samelamin/vibertemis/releases?per_page=100";
    private static final String TAG_PREFIX = "quest-preview-v";
    private static final String MANIFEST_ASSET = "quest-update.json";
    private static final String SIGNATURE_ASSET = "quest-update.json.sig";
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final long APK_BYTES = 4L * 1024L * 1024L;

    /** 3072-bit generation is slow; one key pair serves the whole class. */
    private static final class Keys {
        static final KeyPair PAIR = generate();
        private static KeyPair generate() {
            try {
                return UpdateTestFixture.newKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("fixture key generation failed", e);
            }
        }
    }

    // ---------------------------------------------------------------- selection

    /**
     * The core defect: a broken release that is newer than nothing but
     * older than the good one must not be read at all. Under the old
     * history-wide fetch it was read first and its failure killed the
     * check.
     */
    @Test public void newestVerifiedReleaseWinsWithoutTouchingBrokenOlderRelease() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.4", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, 7L);
        // 0.1.0.9 and 0.1.0.4 are registered nowhere: reading either throws.

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.10", found.version);
        assertEquals(10L, found.versionCode);
        assertEquals(7L, found.sequence);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
        assertNotNull(transport.manifestBytes);
        assertNotNull(transport.signatureBytes);
    }

    /**
     * Ordering is numeric, not textual. Text order puts
     * {@code quest-preview-v0.1.0.9} above
     * {@code quest-preview-v0.1.0.10}, and the list is served in that
     * text order, so this fails if the sort regresses to string
     * comparison.
     */
    @Test public void newestIsChosenByNumericComponentNotStringOrder() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        serveSigned(net, "0.1.0.9", 9L, 7L);
        serveSigned(net, "0.1.0.10", 10L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.10", found.version);
        assertEquals(10L, found.versionCode);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
    }

    /** The installed release is not a candidate, so nothing is fetched. */
    @Test public void installedReleaseNeedsNoManifestFetch() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()))));
        // No manifest is served at all: any manifest read would throw.

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.10", net);
        assertNull(transport.checkForUpdate(10L));
        assertEquals(ONLY_RELEASES, net.requested);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    /**
     * A verified but not-applicable release is skipped, not failed on.
     * Here the newest release was published at the sequence floor, so
     * the next-newest applicable one is selected.
     */
    @Test public void verifiedButNotApplicableReleaseFallsThroughToNextCandidate() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, UpdateRepository.MIN_PUBLISHED_SEQUENCE);
        serveSigned(net, "0.1.0.9", 9L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.9", found.version);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10"),
                manifestUrl("0.1.0.9"), signatureUrl("0.1.0.9")), net.requested);
    }

    /**
     * Unknown version name means no prefilter at all: releases below the
     * running build stay eligible, while newest-first ordering and the
     * short-circuit still hold.
     */
    @Test public void unknownCurrentVersionNameAppliesNoPrefilter() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.3", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, UpdateRepository.MIN_PUBLISHED_SEQUENCE);
        serveSigned(net, "0.1.0.9", 9L, 7L);
        // 0.1.0.3 stays eligible under an unknown installed version, and
        // is only left unread because 0.1.0.9 already satisfied the check.
        serveSigned(net, "0.1.0.3", 3L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "unspecified", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.9", found.version);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10"),
                manifestUrl("0.1.0.9"), signatureUrl("0.1.0.9")), net.requested);
    }

    @Test public void nullCurrentVersionNameAppliesNoPrefilter() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.4", false, bothAssets()))));
        serveSigned(net, "0.1.0.4", 4L, 7L);

        UpdateTransport transport = new UpdateTransport(publicPem(), null, net);
        UpdateManifest found = transport.checkForUpdate(1L);

        assertNotNull(found);
        assertEquals("0.1.0.4", found.version);
    }

    /** Installed-version normalisation, including the unknown cases. */
    @Test public void installedReleaseVersionIsReadFromTheVersionName() {
        assertArrayEquals(new int[]{0, 1, 0, 10},
                UpdateTransport.releaseVersion("0.1.0-quest-preview.10"));
        assertArrayEquals(new int[]{0, 1, 0, 10},
                UpdateTransport.releaseVersion("0.1.0.10"));
        assertArrayEquals(new int[]{0, 1, 0, 11},
                UpdateTransport.releaseVersion("  0.1.0-quest-preview.11  "));
        assertArrayEquals(new int[]{1, 2, 3, 4},
                UpdateTransport.releaseVersion("1.2.3.4"));
        assertNull(UpdateTransport.releaseVersion(null));
        assertNull(UpdateTransport.releaseVersion("unspecified"));
        assertNull(UpdateTransport.releaseVersion("0.1.0"));
        assertNull(UpdateTransport.releaseVersion("1.2.3.4-rc1"));
        assertNull(UpdateTransport.releaseVersion("0.1.0-quest-preview.x"));
    }

    // ---------------------------------------------------------------- integrity

    /**
     * A bad signature on the newest candidate is a visible failure. It
     * must not silently fall back to the older valid release, which
     * would hide a compromised or corrupt newest release.
     */
    @Test public void invalidNewestSignatureFailsWithoutFallingBack() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, 7L);
        corruptSignature(net, "0.1.0.10");
        serveSigned(net, "0.1.0.9", 9L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertCheckFails("Update signature verification failed", transport, 5L);
        // The valid older release was never requested, so the failure is
        // the newest candidate's and not a fallback.
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    /** A newest tag that disagrees with its signed version also fails. */
    @Test public void newestTagMismatchIsAVisibleFailure() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.9", false, bothAssets()))));
        // A genuinely signed manifest, for a different release, served
        // under the newest tag.
        Signed other = sign("0.1.0.9", 10L, 7L);
        net.serveOk(manifestUrl("0.1.0.10"), other.body);
        net.serveOk(signatureUrl("0.1.0.10"), other.signature);
        serveSigned(net, "0.1.0.9", 9L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertCheckFails("Signed release tag mismatch", transport, 5L);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
        assertNull(transport.manifestBytes);
    }

    /** A manifest the transport has no reason to trust is refused. */
    @Test public void manifestSignedByAnUntrustedKeyIsRefused() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        Signed real = sign("0.1.0.10", 10L, 7L);
        net.serveOk(manifestUrl("0.1.0.10"), real.body);
        net.serveOk(signatureUrl("0.1.0.10"), real.signature);

        // Perfectly well formed and correctly signed, but this transport
        // is configured to trust a different public key.
        UpdateTransport transport = new UpdateTransport(
                UpdateTestFixture.pemFor(UntrustedKeys.PAIR), "0.1.0-quest-preview.5", net);

        assertCheckFails("Update signature verification failed", transport, 5L);
        assertNull(transport.manifestBytes);
    }

    // ---------------------------------------------------------------- eligibility

    /** Drafts, malformed tags, other channels and assetless releases are skipped. */
    @Test public void draftMalformedAndAssetlessReleasesAreIgnored() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", true, bothAssets()),
                release(TAG_PREFIX + "0.1.0", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.10.1", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.x1", false, bothAssets()),
                release(TAG_PREFIX + "0.1.0.10 ", false, bothAssets()),
                release(TAG_PREFIX + "v0.1.0.11", false, bothAssets()),
                release("nightly-2026-01-01", false, bothAssets()),
                "{\"draft\":false,\"assets\":[]}",
                release(TAG_PREFIX + "0.1.0.12", false, MANIFEST_ASSET),
                release(TAG_PREFIX + "0.1.0.13", false, SIGNATURE_ASSET),
                release(TAG_PREFIX + "0.1.0.14", false),
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.10", found.version);
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
    }

    /** A release at the published sequence floor is not an update. */
    @Test public void releaseAtTheSequenceFloorIsNotApplicable() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, UpdateRepository.MIN_PUBLISHED_SEQUENCE);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertNull(transport.checkForUpdate(4L));
        assertEquals(urls(RELEASES_URL,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    // ---------------------------------------------------------------- stale state

    /** A check that finds nothing must not leave the previous run's bytes. */
    @Test public void freshCheckClearsStaleBytesWhenNoEligibleRelease() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8("[]"));

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.10", net);
        transport.manifestBytes = new byte[]{1, 2, 3};
        transport.signatureBytes = new byte[]{4, 5, 6};

        assertNull(transport.checkForUpdate(10L));
        assertEquals(ONLY_RELEASES, net.requested);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    /** A failing check must not leave the previous run's bytes either. */
    @Test public void freshCheckClearsStaleBytesWhenTheCheckFails() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        serveSigned(net, "0.1.0.10", 10L, 7L);
        corruptSignature(net, "0.1.0.10");

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        transport.manifestBytes = new byte[]{1};
        transport.signatureBytes = new byte[]{2};

        assertCheckFails("Update signature verification failed", transport, 5L);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    /** A successful check rebinds the bytes to the release it selected. */
    @Test public void successfulCheckBindsBytesToTheSelectedRelease() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        Signed ten = sign("0.1.0.10", 10L, 7L);
        net.serveOk(manifestUrl("0.1.0.10"), ten.body);
        net.serveOk(signatureUrl("0.1.0.10"), ten.signature);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        transport.manifestBytes = new byte[]{1};
        transport.signatureBytes = new byte[]{2};

        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertArrayEquals(ten.body, transport.manifestBytes);
        assertArrayEquals(ten.signature, transport.signatureBytes);
    }

    // ---------------------------------------------------------------- cancellation

    @Test public void checkRequestedAfterCancelDoesNothing() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8("[]"));

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        transport.cancel();

        assertCancelled(transport, 5L);
        assertTrue("cancellation must precede any request", net.requested.isEmpty());
    }

    @Test public void cancellationDuringAMetadataReadStopsTheCheck() throws Exception {
        FakeNetwork net = new FakeNetwork();
        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        Reply list = new Reply(200, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        // Cancel from inside the body read, after the connection is live.
        list.onRead = transport::cancel;
        net.serve(RELEASES_URL, list);
        serveSigned(net, "0.1.0.10", 10L, 7L);

        assertCancelled(transport, 5L);
        assertEquals("no manifest may be requested after cancellation",
                ONLY_RELEASES, net.requested);
        assertEquals(1, net.connections.size());
        assertTrue("an interrupted read must not leak its connection",
                net.connections.get(0).disconnected);
        assertNull(transport.manifestBytes);
        assertNull(transport.signatureBytes);
    }

    // ---------------------------------------------------------------- transport rules

    @Test public void redirectToAnUntrustedHostIsRejected() throws Exception {
        assertFirstHopFails("Untrusted update redirect",
                new Reply(302, null).redirect("https://updates.example.com/releases.json"));
    }

    @Test public void redirectDowngradeToPlainHttpIsRejected() throws Exception {
        assertFirstHopFails("Untrusted update redirect",
                new Reply(302, null).redirect("http://api.github.com/releases"));
    }

    @Test public void redirectToANonDefaultPortIsRejected() throws Exception {
        assertFirstHopFails("Untrusted update redirect",
                new Reply(302, null).redirect("https://api.github.com:8443/releases"));
    }

    @Test public void redirectWithCredentialsInTheUrlIsRejected() throws Exception {
        assertFirstHopFails("Untrusted update redirect",
                new Reply(302, null).redirect("https://user@api.github.com/releases"));
    }

    @Test public void redirectWithoutALocationIsRejected() throws Exception {
        assertFirstHopFails("Invalid update redirect", new Reply(302, null));
    }

    /** The redirect budget is unchanged: a sixth hop is never attempted. */
    @Test public void redirectBudgetIsCappedAtFiveHops() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serve(RELEASES_URL,
                new Reply(302, null).redirect("https://github.com/r/1"));
        for (int hop = 1; hop <= 4; hop++) {
            net.serve("https://github.com/r/" + hop,
                    new Reply(302, null).redirect("https://github.com/r/" + (hop + 1)));
        }

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertCheckFails("Too many update redirects", transport, 5L);
        assertEquals(Arrays.asList(RELEASES_URL,
                "https://github.com/r/1", "https://github.com/r/2",
                "https://github.com/r/3", "https://github.com/r/4"), net.requested);
    }

    @Test public void allowedHttpsRedirectIsFollowed() throws Exception {
        String asset = "https://release-assets.githubusercontent.com/releases/listing";
        FakeNetwork net = new FakeNetwork();
        net.serve(RELEASES_URL, new Reply(302, null).redirect(asset));
        net.serve(asset, new Reply(200, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets())))));
        serveSigned(net, "0.1.0.10", 10L, 7L);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        UpdateManifest found = transport.checkForUpdate(5L);

        assertNotNull(found);
        assertEquals("0.1.0.10", found.version);
        assertEquals(Arrays.asList(RELEASES_URL, asset,
                manifestUrl("0.1.0.10"), signatureUrl("0.1.0.10")), net.requested);
    }

    @Test public void rateLimitingIsReportedActionably() throws Exception {
        assertFirstHopFails("Update checks are temporarily rate limited. Try again later",
                new Reply(403, null));
        assertFirstHopFails("Update checks are temporarily rate limited. Try again later",
                new Reply(429, null));
    }

    @Test public void otherHttpErrorsReportTheStatusCode() throws Exception {
        assertFirstHopFails("Update server returned HTTP 404", new Reply(404, null));
        assertFirstHopFails("Update server returned HTTP 500", new Reply(500, null));
        assertFirstHopFails("Update server returned HTTP 503", new Reply(503, null));
    }

    @Test public void oversizedReleaseListingIsRefused() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, new byte[2 * 1024 * 1024 + 1]);

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertCheckFails("Update metadata too large", transport, 5L);
        assertNull(transport.manifestBytes);
    }

    @Test public void oversizedManifestIsRefusedBeforeTheSignatureIsRequested() throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serveOk(RELEASES_URL, utf8(releases(
                release(TAG_PREFIX + "0.1.0.10", false, bothAssets()))));
        net.serveOk(manifestUrl("0.1.0.10"), new byte[65537]);
        // The signature is registered nowhere: asking for it throws, so a
        // request past the size limit fails the test instead of passing.

        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);

        assertCheckFails("Update metadata too large", transport, 5L);
        assertEquals(urls(RELEASES_URL, manifestUrl("0.1.0.10")), net.requested);
        assertNull(transport.manifestBytes);
    }

    // ---------------------------------------------------------------- fixtures

    private static String publicPem() { return UpdateTestFixture.pemFor(Keys.PAIR); }

    /** A second, unrelated fixture key, so "trusted" is a real distinction. */
    private static final class UntrustedKeys {
        static final KeyPair PAIR = generate();
        private static KeyPair generate() {
            try {
                return UpdateTestFixture.newKeyPair();
            } catch (Exception e) {
                throw new IllegalStateException("fixture key generation failed", e);
            }
        }
    }

    private static final class Signed {
        final byte[] body;
        final byte[] signature;
        Signed(byte[] body, byte[] signature) { this.body = body; this.signature = signature; }
    }

    /** A real signature over a real, schema-valid manifest body. */
    private static Signed sign(String version, long versionCode, long sequence) throws Exception {
        String filename = "vibertemis-quest-preview-" + version + ".apk";
        String json = "{\"schema\":1"
                + ",\"channel\":\"quest-preview\""
                + ",\"native_protocol\":\"" + UpdateManifest.PROTOCOL + "\""
                + ",\"sequence\":" + sequence
                + ",\"version\":\"" + version + "\""
                + ",\"assets\":{\"android\":{"
                + "\"filename\":\"" + filename + "\""
                + ",\"url\":\"" + UpdateManifest.PREFIX + TAG_PREFIX + version + "/" + filename + "\""
                + ",\"bytes\":" + APK_BYTES
                + ",\"version_code\":" + versionCode
                + ",\"sha256\":\"" + DIGEST + "\""
                + ",\"package\":\"" + UpdateTestFixture.packageName(UpdateTestFixture.context()) + "\""
                + ",\"signer_sha256\":\"" + UpdateTestFixture.SIGNER_SHA256 + "\""
                + "}}}";
        byte[] body = utf8(json);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(Keys.PAIR.getPrivate());
        signer.update(body);
        return new Signed(body, signer.sign());
    }

    private static void serveSigned(FakeNetwork net, String version, long versionCode, long sequence)
            throws Exception {
        Signed signed = sign(version, versionCode, sequence);
        net.serveOk(manifestUrl(version), signed.body);
        net.serveOk(signatureUrl(version), signed.signature);
    }

    /** Flip a signature bit: still 384 bytes, but no longer valid. */
    private static void corruptSignature(FakeNetwork net, String version) {
        Reply reply = net.replies.get(signatureUrl(version));
        byte[] broken = reply.body.clone();
        broken[11] ^= 0x5A;
        net.serveOk(signatureUrl(version), broken);
    }

    private static String manifestUrl(String version) {
        return UpdateManifest.PREFIX + TAG_PREFIX + version + "/" + MANIFEST_ASSET;
    }

    private static String signatureUrl(String version) {
        return UpdateManifest.PREFIX + TAG_PREFIX + version + "/" + SIGNATURE_ASSET;
    }

    private static String release(String tag, boolean draft, String... assets) {
        StringBuilder json = new StringBuilder("{\"tag_name\":\"").append(tag)
                .append("\",\"draft\":").append(draft).append(",\"assets\":[");
        for (int i = 0; i < assets.length; i++) {
            if (i > 0) json.append(',');
            json.append("{\"name\":\"").append(assets[i]).append("\"}");
        }
        return json.append("]}").toString();
    }

    private static String releases(String... entries) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < entries.length; i++) {
            if (i > 0) json.append(',');
            json.append(entries[i]);
        }
        return json.append(']').toString();
    }

    private static String[] bothAssets() {
        return new String[]{MANIFEST_ASSET, SIGNATURE_ASSET};
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static List<String> urls(String... addresses) { return Arrays.asList(addresses); }

    private static final List<String> ONLY_RELEASES = Collections.singletonList(RELEASES_URL);

    private static void assertCheckFails(String message, UpdateTransport transport, long currentVersion)
            throws Exception {
        try {
            UpdateManifest found = transport.checkForUpdate(currentVersion);
            fail("expected \"" + message + "\" but the check returned " + found);
        } catch (IOException e) {
            assertEquals(message, e.getMessage());
        }
    }

    /** Cancellation is an interruption, not a transport error. */
    private static void assertCancelled(UpdateTransport transport, long currentVersion) throws Exception {
        try {
            transport.checkForUpdate(currentVersion);
            fail("expected the check to be cancelled");
        } catch (InterruptedIOException e) {
            assertEquals("Update cancelled or timed out", e.getMessage());
        }
    }

    /** Drives a one-hop failure and asserts nothing was retried. */
    private static void assertFirstHopFails(String message, Reply reply) throws Exception {
        FakeNetwork net = new FakeNetwork();
        net.serve(RELEASES_URL, reply);
        UpdateTransport transport =
                new UpdateTransport(publicPem(), "0.1.0-quest-preview.5", net);
        assertCheckFails(message, transport, 5L);
        assertEquals(ONLY_RELEASES, net.requested);
        assertNull(transport.manifestBytes);
    }

    // ---------------------------------------------------------------- fakes

    private static final class Reply {
        final int code;
        final Map<String, String> headers = new LinkedHashMap<>();
        final byte[] body;
        Runnable onRead;

        Reply(int code, byte[] body) {
            this.code = code;
            this.body = body == null ? new byte[0] : body;
        }

        Reply redirect(String location) {
            headers.put("Location", location);
            return this;
        }
    }

    /**
     * Serves replies by exact address and records every request. An
     * unregistered address fails, so an unintended fetch cannot pass
     * unnoticed.
     */
    private static final class FakeNetwork implements UpdateTransport.ConnectionFactory {
        final Map<String, Reply> replies = new LinkedHashMap<>();
        final List<String> requested = new ArrayList<>();
        final List<FakeHttp> connections = new ArrayList<>();

        FakeNetwork serve(String address, Reply reply) {
            replies.put(address, reply);
            return this;
        }

        FakeNetwork serveOk(String address, byte[] body) {
            return serve(address, new Reply(200, body));
        }

        @Override public HttpURLConnection open(URL url) throws Exception {
            String address = url.toString();
            requested.add(address);
            Reply reply = replies.get(address);
            if (reply == null) throw new IOException("Unexpected update request: " + address);
            FakeHttp connection = new FakeHttp(url, reply);
            connections.add(connection);
            return connection;
        }
    }

    /** No sockets, no DNS, no real IO: purely the reply the test set up. */
    private static final class FakeHttp extends HttpURLConnection {
        private final Reply reply;
        private final boolean error;
        boolean disconnected;

        FakeHttp(URL url, Reply reply) {
            super(url);
            this.reply = reply;
            this.error = reply.code >= 400;
        }

        @Override public void connect() { }

        @Override public void disconnect() { disconnected = true; }

        @Override public boolean usingProxy() { return false; }

        @Override public int getResponseCode() { return reply.code; }

        @Override public String getHeaderField(String name) {
            for (Map.Entry<String, String> header : reply.headers.entrySet()) {
                if (name.equalsIgnoreCase(header.getKey())) return header.getValue();
            }
            return null;
        }

        @Override public InputStream getInputStream() throws IOException {
            if (error) throw new IOException("HTTP " + reply.code);
            InputStream body = new ByteArrayInputStream(reply.body);
            return reply.onRead == null ? body : new HookStream(body, reply.onRead);
        }

        @Override public InputStream getErrorStream() {
            return new ByteArrayInputStream(reply.body);
        }
    }

    /** Runs a hook on the first read, so a test can act mid-body. */
    private static final class HookStream extends InputStream {
        private final InputStream delegate;
        private Runnable hook;

        HookStream(InputStream delegate, Runnable hook) {
            this.delegate = delegate;
            this.hook = hook;
        }

        private void fire() {
            Runnable current = hook;
            hook = null;
            if (current != null) current.run();
        }

        @Override public int read() throws IOException { fire(); return delegate.read(); }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            fire();
            return delegate.read(b, off, len);
        }

        @Override public int available() throws IOException { return delegate.available(); }

        @Override public void close() throws IOException { delegate.close(); }
    }
}
