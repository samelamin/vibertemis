package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.view.View;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowActivityManager;

import java.io.File;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The journey the owner asked for: ONE Update tap that carries the
 * release all the way to the OS installer, plus the races and
 * resumptions that could quietly break that promise.
 *
 * <p>Covered here:
 * <ul>
 *   <li><b>one click end to end</b> — the bytes arrive through the real
 *       {@link UpdateTransport} (which serves a digest-verified cached
 *       {@code update.apk} instead of reaching GitHub), the activity
 *       verifies package / versionCode / signer and launches
 *       {@code ACTION_INSTALL_PACKAGE} with no second tap;</li>
 *   <li><b>pinned target vs new metadata</b> — a background check that
 *       publishes a newer release mid-download must not retarget the
 *       running attempt;</li>
 *   <li><b>cancel race</b> — cancelling while the download is in
 *       flight must not install;</li>
 *   <li><b>busy before hand-off</b> — the button is disabled and the
 *       label still names the pinned release;</li>
 *   <li><b>Settings round trip</b> — returning from the
 *       source-permission screen resumes the installer exactly once,
 *       and a cancelled or stale generation never launches;</li>
 *   <li><b>installer return unknown</b> — a return with no callback, or
 *       with the old version still installed, is never reported as
 *       completed.</li>
 * </ul>
 *
 * <p>Only the remote is faked: a gated {@link
 * UpdatesActivity.TransportFactory} stands in for the network so a
 * test can pause an attempt mid-flight. Every verification step and
 * the installer hand-off are the production code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UpdatesActivitySingleUpdateActionTest {

    private KeyPair keyPair;
    private String keyPem;
    private long installedVersionCode;
    /** Bytes the transport will serve for a release, keyed by version. */
    private final java.util.Map<String, byte[]> payloads = new java.util.HashMap<>();
    private final GatedTransportFactory transport = new GatedTransportFactory();

    @Before public void setup() throws Exception {
        keyPair = UpdateTestFixture.newKeyPair();
        keyPem = UpdateTestFixture.pemFor(keyPair);
        installedVersionCode = UpdateTestFixture.installedVersionCode(UpdateTestFixture.context());
        UpdateRepositoryProvider.installCheckSourceFactoryForTest(
                (context, key) -> versionCode -> UpdateRepository.CheckSource.Result.none());
        UpdateTestFixture.resetFileProviderStrategyCache();
        UpdateRepositoryProvider.reset();
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                installedVersionCode, true);
        UpdatesActivity.installTransportFactoryForTest(transport);
        UpdatesActivity.installTrustKeyProviderForTest(a -> keyPem);
        ShadowActivityManager am = Shadows.shadowOf(
                (android.app.ActivityManager) UpdateTestFixture.context()
                        .getSystemService(Activity.ACTIVITY_SERVICE));
        am.setProcesses(new ArrayList<>());
    }

    @After public void teardown() throws Exception {
        UpdateRepositoryProvider.reset();
        UpdateRepositoryProvider.installCheckSourceFactoryForTest(null);
        transport.unblock();
        UpdatesActivity.installTransportFactoryForTest(new UpdatesActivity.TransportFactory() {
            @Override public UpdateTransport create(String trustedKey) {
                return new UpdateTransport(trustedKey);
            }
        });
    }

    // ------------------------------------------------------------------
    //  1. One click, end to end
    // ------------------------------------------------------------------

    @Test public void oneTapDownloadsVerifiesAndOpensInstaller() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            assertEquals("the single action must offer the update",
                    UpdatesActivity.PrimaryAction.UPDATE, a.primaryActionForTest());
            assertEquals("Update to 0.1.0.7", a.primaryLabelForTest());

            a.primaryButtonForTest().performClick();

            idleUntil(a, a::handedToSystemForTest);
            assertEquals("exactly one download attempt", 1, transport.created.get());
            assertEquals("the download must be for the pinned release",
                    "0.1.0.7", transport.manifest.get().version);
            assertTrue("the verified release must be cached for reuse",
                    repo().snapshot().hasDownloaded());
            assertEquals("the cache must be bound to the pinned version",
                    "0.1.0.7", repo().snapshot().downloaded.version);

            ShadowActivity.IntentForResult launched = installerIntent(a);
            assertNotNull("the OS installer must be launched", launched);
            assertEquals(Intent.ACTION_INSTALL_PACKAGE, launched.intent.getAction());
            assertEquals("application/vnd.android.package-archive", launched.intent.getType());
            assertNotNull("the APK must be handed over as a content URI",
                    launched.intent.getData());
            assertEquals("the installer must be given a FileProvider content URI",
                    "content", launched.intent.getData().getScheme());
            assertTrue("the read grant must be attached so the installer can read it",
                    (launched.intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertTrue("the activity must be awaiting the system answer",
                    a.awaitingSystemForTest());
            assertTrue("the status must be honest about waiting on Android: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("Waiting for Android"));
            assertFalse("the primary must be disabled once the OS owns the screen",
                    a.isPrimaryEnabledForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  2. The target is pinned; new metadata cannot retarget it
    // ------------------------------------------------------------------

    @Test public void newerMetadataMidDownloadCannotRetargetTheAttempt() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            transport.block();
            a.primaryButtonForTest().performClick();
            idleUntil(a, transport::entered);
            assertEquals("DOWNLOADING", a.runningStageForTest());
            assertEquals("the attempt pins 0.1.0.7", "0.1.0.7", a.pinnedVersionForTest());

            // A background check publishes a newer release while the
            // download is still in flight.
            publishAvailable("0.1.0.9", 9);
            idle();

            assertEquals("metadata must not move the pinned target",
                    "0.1.0.7", a.pinnedVersionForTest());
            assertEquals("the label must keep naming the pinned release",
                    "Update to 0.1.0.7", a.primaryLabelForTest());
            assertFalse("the primary must stay disabled while pinned",
                    a.isPrimaryEnabledForTest());

            transport.unblock();
            idleUntil(a, a::handedToSystemForTest);

            assertEquals("the download must still be the originally chosen release",
                    "0.1.0.7", transport.manifest.get().version);
            assertEquals("the cached release must be the pinned one, not the newer one",
                    "0.1.0.7", repo().snapshot().downloaded.version);
            assertTrue("the newer release must still be advertised for later",
                    repo().snapshot().hasAvailable());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void newerMetadataAfterHandOffOffersTheNextUpdate() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            ShadowActivity.IntentForResult first = installerIntent(a);
            assertNotNull("the installer must be launched", first);

            // A newer release is advertised while the installer is up.
            publishAvailable("0.1.0.9", 9);
            idle();

            // The user comes back with no callback at all: the outcome is
            // unknown, so the screen must keep naming the release it
            // pinned instead of offering the newer one.
            ctl.pause().resume();
            idleUntil(a, a::installerUnconfirmedForTest);
            assertEquals("the retry must stay on the pinned release",
                    "0.1.0.7", a.pinnedVersionForTest());
            assertEquals("the retry must not be retargeted by newer metadata",
                    UpdatesActivity.PrimaryAction.RETRY_UNCONFIRMED, a.primaryActionForTest());
            assertEquals("Retry", a.primaryLabelForTest());
            assertFalse("the unconfirmed status must name the pinned release, not the new one: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("0.1.0.9"));

            // Android then reports that the install did not happen: the
            // attempt ends there, and the newest metadata is what the
            // screen offers next.
            a.onActivityResult(first.requestCode, Activity.RESULT_CANCELED, null);
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));
            assertEquals("the reported non-install must not relaunch anything",
                    1, countInstallerLaunches(a));

            assertEquals("the next offer follows the newest metadata",
                    "Update to 0.1.0.9", a.primaryLabelForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  3. Cancel race
    // ------------------------------------------------------------------

    @Test public void cancelMidDownloadNeverOpensTheInstaller() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            transport.block();
            a.primaryButtonForTest().performClick();
            idleUntil(a, transport::entered);
            assertTrue("cancel must be offered before the hand-off",
                    a.isCancelVisibleForTest());

            a.cancelButtonForTest().performClick();
            idle();
            assertNull("cancel must release the pinned target", a.pinnedVersionForTest());
            assertEquals("cancel must return the screen to idle",
                    "IDLE", a.runningStageForTest());
            assertFalse("cancel must hide itself", a.isCancelVisibleForTest());

            transport.unblock();
            idleFor(500);

            assertFalse("a cancelled attempt must never open the installer",
                    a.handedToSystemForTest());
            assertNull("no installer intent may be launched", installerIntent(a));
            assertFalse("no cached release may be promoted by a cancelled attempt",
                    repo().snapshot().hasDownloaded());
            assertTrue("the cancel must be explained: " + a.windowStatusText(),
                    a.windowStatusText().toLowerCase().contains("cancelled"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void cancelRetiresOnceTheOsOwnsTheScreen() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            assertEquals("cancel is not ours once the OS owns the screen",
                    View.GONE, a.cancelVisibilityForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  4. Busy before hand-off
    // ------------------------------------------------------------------

    @Test public void busyBeforeHandoffDisablesTheButtonAndKeepsTheLabel() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            transport.block();
            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "DOWNLOADING".equals(a.runningStageForTest()));

            assertFalse("the primary must be disabled while an attempt runs",
                    a.isPrimaryEnabledForTest());
            assertEquals("the label must still name the release in flight",
                    "Update to 0.1.0.7", a.primaryLabelForTest());
            assertEquals("the action must be NONE while busy",
                    UpdatesActivity.PrimaryAction.NONE, a.primaryActionForTest());
            assertTrue("cancel must be available before the hand-off",
                    a.isCancelVisibleForTest());
            assertTrue("the status must describe the real stage: " + a.windowStatusText(),
                    a.windowStatusText().contains("Downloading update 0.1.0.7"));

            // A tap on the disabled button must not start a second
            // attempt.
            a.primaryButtonForTest().performClick();
            idle();
            assertEquals("a disabled primary must not start a second download",
                    1, transport.created.get());

            transport.unblock();
            idleUntil(a, a::handedToSystemForTest);
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  5. Source-permission Settings round trip
    // ------------------------------------------------------------------

    @Test public void settingsReturnResumesTheInstallerExactlyOnce() throws Exception {
        publishAvailable("0.1.0.7", 7);
        // Start with the permission missing so the dialog is shown.
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                installedVersionCode, false);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            assertEquals("the stage is the hand-off while the dialog is up",
                    "INSTALLER", a.runningStageForTest());
            assertTrue("cancel is still ours before the OS is involved",
                    a.isCancelVisibleForTest());

            // Answer the dialog with "Open settings", then grant the
            // permission while we are away.
            latestDialog(a).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            ShadowActivity.IntentForResult settings = nextIntent(a);
            assertNotNull("the Settings screen must be launched", settings);
            assertEquals(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    settings.intent.getAction());
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                    installedVersionCode, true);
            ctl.pause().resume();
            idleUntil(a, () -> a.handedToSystemForTest() && a.awaitingSystemForTest());

            assertNotNull("the installer must be resumed after the grant",
                    installerIntent(a));
            assertEquals("the resume must launch the installer exactly once",
                    1, countInstallerLaunches(a));
            assertEquals("the pinned release must survive the round trip",
                    "0.1.0.7", repo().snapshot().downloaded.version);
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void cancelledAttemptCannotLaunchAfterSettingsReturn() throws Exception {
        publishAvailable("0.1.0.7", 7);
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                installedVersionCode, false);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);

            // The user goes to Settings and then changes their mind.
            latestDialog(a).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            int staleRequestCode = nextIntent(a).requestCode;
            a.cancelButtonForTest().performClick();
            idle();
            assertNull("cancel must release the pinned target", a.pinnedVersionForTest());

            // A late grant for the abandoned attempt must not launch.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                    installedVersionCode, true);
            a.onActivityResult(staleRequestCode, Activity.RESULT_OK, null);
            idle();
            ctl.pause().resume();
            idleFor(300);

            assertNull("a cancelled attempt must never launch the installer",
                    installerIntent(a));
            assertFalse("a cancelled attempt must not claim a hand-off",
                    a.handedToSystemForTest());
            assertTrue("the status must say the update was cancelled: "
                            + a.windowStatusText(),
                    a.windowStatusText().toLowerCase().contains("cancel"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void deniedSettingsReturnKeepsTheVerifiedCache() throws Exception {
        publishAvailable("0.1.0.7", 7);
        UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                installedVersionCode, false);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::awaitingPermissionForTest);
            latestDialog(a).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            idle();
            ShadowActivity.IntentForResult settings = nextIntent(a);
            assertNotNull("the Settings screen must be launched", settings);

            // The user comes back without granting. Android delivers
            // the result for the request we actually made.
            ctl.pause();
            a.onActivityResult(settings.requestCode, Activity.RESULT_CANCELED, null);
            ctl.resume();
            idle();

            assertEquals("a denial must clear the busy state",
                    "IDLE", a.runningStageForTest());
            assertNull("a denial must never launch the installer", installerIntent(a));
            assertTrue("the verified cache must survive the denial",
                    repo().snapshot().hasDownloaded());
            assertEquals("the update must be offered again",
                    "Update to 0.1.0.7", a.primaryLabelForTest());
            assertTrue("the status must name the reason: " + a.windowStatusText(),
                    a.windowStatusText().contains("permission was not granted"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  6. Installer return is unknown, not success
    // ------------------------------------------------------------------

    @Test public void returnWithNoCallbackIsUnconfirmedKeepsCacheAndRetriesOnce() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            org.robolectric.shadows.ShadowActivity.IntentForResult first =
                    installerIntent(a);
            assertNotNull("the installer must be launched", first);

            // The user swipes the installer away; Android delivers no
            // result at all. That is unknown, not a failed install.
            ctl.pause().resume();
            idleUntil(a, () -> a.installerUnconfirmedForTest());

            assertFalse("an absent callback is not a completed install",
                    a.windowStatusText().contains("Update 0.1.0.7 installed."));
            assertTrue("the status must say the outcome is unknown: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("not confirmed"));
            assertTrue("the verified cache must be kept for a retry",
                    repo().snapshot().hasDownloaded());
            assertTrue("the status must name the release in question: "
                            + a.windowStatusText(),
                    a.windowStatusText().contains("0.1.0.7"));
            assertEquals("the retry must be one plain Retry",
                    "Retry", a.primaryLabelForTest());
            assertEquals(UpdatesActivity.PrimaryAction.RETRY_UNCONFIRMED,
                    a.primaryActionForTest());

            // An explicit retry re-offers the same verified bytes: no
            // second download, and a fresh launch.
            int before = transport.created.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);
            assertEquals("a retry must not download again",
                    before, transport.created.get());
            org.robolectric.shadows.ShadowActivity.IntentForResult second =
                    latestInstallerIntent(a);
            assertNotNull("the retry must open the installer again", second);
            assertTrue("the retry must use a new request code: " + second.requestCode,
                    second.requestCode != first.requestCode);
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void confirmedInstallClearsTheCacheAndReportsSuccess() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            a.primaryButtonForTest().performClick();
            idleUntil(a, a::handedToSystemForTest);

            // Android really installed it this time.
            UpdateTestFixture.installSigningIdentity(UpdateTestFixture.context(),
                    installedVersionCode + 1, true);
            a.onActivityResult(latestInstallerIntent(a).requestCode, Activity.RESULT_OK, null);
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest()));

            assertFalse("a confirmed install must clear the cache",
                    repo().snapshot().hasDownloaded());
            assertEquals("the status must report the confirmed install",
                    "Update 0.1.0.7 installed.", a.windowStatusText());
            assertEquals("with nothing pending the primary offers a check",
                    UpdatesActivity.PrimaryAction.CHECK, a.primaryActionForTest());
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void foreignSignerIsRefusedAndTheCacheIsNotKept() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            // Serve bytes whose archive identity carries a different
            // signer, so the production signer equality check refuses.
            transport.signerOverride = new android.content.pm.Signature(new byte[]{1, 1, 1});
            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest())
                    && a.currentActionErrorForTest() != null);

            assertNull("a foreign signer must never open the installer",
                    installerIntent(a));
            assertFalse("a foreign signer must not be cached",
                    repo().snapshot().hasDownloaded());
            assertEquals("a verification failure must offer Retry",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            assertEquals("Retry", a.primaryLabelForTest());
            String text = a.windowStatusText();
            assertTrue("the refusal must be explained in plain words: " + text,
                    text.contains("signer does not match"));
            assertFalse("the status must not leak a stack trace: " + text,
                    text.contains("\tat ") || text.contains("Exception"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    @Test public void missingCandidateSigningInfoIsRefusedWithAReadableError() throws Exception {
        publishAvailable("0.1.0.7", 7);
        ActivityController<UpdatesActivity> ctl = openActivity();
        try {
            UpdatesActivity a = ctl.get();
            // The release-bound copy keeps its package name, versionCode
            // and digest, so everything before the signer reads normally,
            // but the archive reports no signing information at all.
            transport.stripSigningInfo = true;
            a.primaryButtonForTest().performClick();
            idleUntil(a, () -> "IDLE".equals(a.runningStageForTest())
                    && a.currentActionErrorForTest() != null);

            assertNull("an APK with no signing information must never open the installer",
                    installerIntent(a));
            assertFalse("and it must not be cached", repo().snapshot().hasDownloaded());
            assertEquals("a refusal must offer Retry",
                    UpdatesActivity.PrimaryAction.RETRY, a.primaryActionForTest());
            String text = a.windowStatusText();
            assertTrue("the refusal must name the missing signing information: " + text,
                    text.contains("signing information is missing"));
            assertFalse("the status must not leak a stack trace: " + text,
                    text.contains("\tat ") || text.contains("Exception"));
        } finally {
            ctl.pause().stop().destroy();
        }
    }

    // ------------------------------------------------------------------
    //  Fixtures
    // ------------------------------------------------------------------

    /** Publish a signed available release and stage the bytes the
     *  transport will serve for it. */
    private byte[] publishAvailable(String version, long sequence) throws Exception {
        byte[] apk = ("apk-" + version + "-" + sequence)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        payloads.put(version, apk);
        byte[] body = body(version, sequence, apk);
        byte[] sig = sign(body);
        repo().recordAvailable(UpdateManifest.verify(body, sig, keyPem), body, sig);
        return apk;
    }

    private byte[] body(String version, long sequence, byte[] apk) throws Exception {
        String filename = "vibertemis-quest-preview-" + version + ".apk";
        String url = UpdateManifest.PREFIX + "quest-preview-v" + version + "/" + filename;
        return new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android",
                        new JSONObject()
                                .put("filename", filename)
                                .put("url", url)
                                .put("bytes", apk.length)
                                .put("sha256", UpdateTestFixture.hex(UpdateTestFixture.sha256(apk)))
                                .put("package", UpdateTestFixture.packageName(UpdateTestFixture.context()))
                                .put("version_code", installedVersionCode + 1)
                                .put("signer_sha256", UpdateTestFixture.SIGNER_SHA256)))
                .toString().getBytes("UTF-8");
    }

    private byte[] sign(byte[] body) throws Exception {
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(body);
        return s.sign();
    }

    private UpdateRepository repo() {
        return UpdateRepositoryProvider.get(UpdateTestFixture.context());
    }

    private ActivityController<UpdatesActivity> openActivity() {
        // Boot the provider so the publish helpers and the activity
        // share one repository.
        repo();
        return Robolectric.buildActivity(UpdatesActivity.class).create().start().resume().visible();
    }

    private static android.app.AlertDialog latestDialog(UpdatesActivity a) {
        android.app.AlertDialog dialog = (android.app.AlertDialog)
                org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull("a dialog must be on screen", dialog);
        assertTrue("the latest dialog must be showing", dialog.isShowing());
        return dialog;
    }

    /** Every intent this activity launched for result, in order. Read
     *  as often as you like: Robolectric's queue is drained once and the
     *  history is kept. */
    private static List<ShadowActivity.IntentForResult> allIntents(UpdatesActivity a) {
        return UpdateTestFixture.launchedIntents(a);
    }

    /** The oldest launch of any kind, if any. */
    private static ShadowActivity.IntentForResult nextIntent(UpdatesActivity a) {
        List<ShadowActivity.IntentForResult> all = allIntents(a);
        return all.isEmpty() ? null : all.get(0);
    }

    /** The oldest ACTION_INSTALL_PACKAGE launch, if any. */
    private static ShadowActivity.IntentForResult installerIntent(UpdatesActivity a) {
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) return i;
        }
        return null;
    }

    /** The newest ACTION_INSTALL_PACKAGE launch, if any. */
    private static ShadowActivity.IntentForResult latestInstallerIntent(UpdatesActivity a) {
        ShadowActivity.IntentForResult newest = null;
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) newest = i;
        }
        return newest;
    }

    private static int countInstallerLaunches(UpdatesActivity a) {
        int count = 0;
        for (ShadowActivity.IntentForResult i : allIntents(a)) {
            if (Intent.ACTION_INSTALL_PACKAGE.equals(i.intent.getAction())) count++;
        }
        return count;
    }

    private static void idle() {
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private static void idleUntil(UpdatesActivity a, BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            idle();
            if (done.getAsBoolean()) return;
            Thread.sleep(5);
        }
        idle();
        assertTrue("timed out waiting for the expected state (stage="
                        + a.runningStageForTest() + ", status=" + a.windowStatusText() + ")",
                done.getAsBoolean());
    }

    private static void idleFor(long millis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (System.nanoTime() < deadline) {
            idle();
            Thread.sleep(5);
        }
        idle();
    }

    /**
     * Stands in for the network. The transport it hands back is the
     * real {@link UpdateTransport}, so the digest, size and
     * package-identity checks all run in production; the gate lets a
     * test hold an attempt open at a chosen point.
     */
    private final class GatedTransportFactory implements UpdatesActivity.TransportFactory {
        /** One production download worker builds exactly one transport,
         *  so this counts download attempts. */
        final AtomicInteger created = new AtomicInteger();
        final AtomicReference<UpdateManifest> manifest = new AtomicReference<>();
        final AtomicReference<Boolean> enteredFlag = new AtomicReference<>(false);
        /** When set, the archive identity is published with this
         *  signer so the production signer check can be exercised. */
        volatile android.content.pm.Signature signerOverride;
        /** When set, the release-bound copy is published with no signing
         *  information, so the production check has nothing to read a
         *  signer out of. */
        volatile boolean stripSigningInfo;

        private volatile CountDownLatch gate = new CountDownLatch(0);
        private volatile CountDownLatch enteredLatch = new CountDownLatch(1);

        void block() {
            gate = new CountDownLatch(1);
            enteredLatch = new CountDownLatch(1);
            enteredFlag.set(false);
        }

        void unblock() { gate.countDown(); }

        boolean entered() {
            if (Boolean.TRUE.equals(enteredFlag.get())) return true;
            try {
                if (enteredLatch.await(50, TimeUnit.MILLISECONDS)) {
                    enteredFlag.set(Boolean.TRUE);
                    return true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        @Override public UpdateTransport create(String trustedKey) {
            created.incrementAndGet();
            // Resolve which release this worker was started for *before*
            // the gate opens. An attempt pins its manifest at click time,
            // so a background check publishing a newer release while the
            // worker waits must not change what the network would serve:
            // the fake has to answer for the release that is downloading,
            // which is what lets the test prove the attempt is not
            // retargeted.
            UpdateManifest pinned = offeredRelease();
            manifest.set(pinned);
            enteredLatch.countDown();
            UpdateTransport local = new UpdateTransport(trustedKey);
            try { gate.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            // Stage the bytes the production transport will find: a
            // digest-verified update.apk in the download directory is
            // the transport's own cache short-circuit, so no network
            // is involved and every check below still runs.
            try {
                stageTransportPayload(pinned);
            } catch (Exception e) {
                throw new IllegalStateException("could not stage the updater payload", e);
            }
            return local;
        }

        /** The release the screen is offering at this moment, which is
         *  what a download starting now has to fetch. */
        private UpdateManifest offeredRelease() {
            UpdateRepository.Snapshot snap = repo().snapshot();
            UpdateManifest target = snap.available;
            if (snap.hasDownloaded() && snap.hasAvailable()
                    && snap.available.versionCode == snap.downloaded.versionCode
                    && snap.available.sha256.equals(snap.downloaded.sha256)) {
                target = snap.downloaded;
            }
            return target;
        }

        private void stageTransportPayload(UpdateManifest target) throws Exception {
            File dir = new File(UpdateTestFixture.context().getCacheDir(), "updates");
            if (target == null) return;
            byte[] bytes = payloads.get(target.version);
            if (bytes == null) return;
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File apk = new File(dir, "update.apk");
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                out.write(bytes);
            }
            android.content.pm.Signature signer = signerOverride != null
                    ? signerOverride : UpdateTestFixture.SIGNER;
            UpdateTestFixture.publishArchiveInfo(UpdateTestFixture.context(),
                    apk, target.versionCode, signer);
            // The activity verifies and hands over the release-bound
            // copy the repository persisted, so that path needs the
            // archive identity as well.
            UpdateTestFixture.publishDownloadedArchiveInfo(UpdateTestFixture.context(),
                    target.version, target.versionCode, signer);
            if (stripSigningInfo) {
                UpdateTestFixture.publishDownloadedArchiveInfoWithoutSigningInfo(
                        UpdateTestFixture.context(), target.version, target.versionCode);
            }
        }
    }
}
