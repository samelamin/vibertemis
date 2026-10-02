package com.vibertemis.quest.update;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowActivityManager;

import java.io.File;
import java.io.FileOutputStream;
import java.security.KeyPair;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Renders the redesigned updates screen ("Quest update" artboard) to PNG
 * under app/build/reports/quest-ui so it can be compared with the design,
 * and pins the visible pieces the design calls for: version chips, the
 * Download / Verify / Install steps and the details panel.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 28)
public class UpdatesScreenshotTest {

    private KeyPair keyPair;
    private String keyPem;
    private long installedVersionCode;
    private File outDir;

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
        UpdatesActivity.installTrustKeyProviderForTest(a -> keyPem);
        ShadowActivityManager am = Shadows.shadowOf(
                (android.app.ActivityManager) UpdateTestFixture.context()
                        .getSystemService(Activity.ACTIVITY_SERVICE));
        am.setProcesses(new ArrayList<>());
        outDir = new File("app/build/reports/quest-ui");
        if (!outDir.exists()) outDir = new File("build/reports/quest-ui");
        outDir.mkdirs();
    }

    @After public void teardown() {
        UpdateRepositoryProvider.reset();
        UpdateRepositoryProvider.installCheckSourceFactoryForTest(null);
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-land-mdpi")
    public void availableUpdate_headset() throws Exception {
        publishAvailable("0.1.0.13", 13);
        UpdatesActivity a = render("updates_available_1000x700.png", 1000, 700);
        assertEquals("the target version chip names the offered release",
                View.VISIBLE, a.toVersionChipVisibilityForTest());
        assertEquals("the three steps are shown for an offered release",
                View.VISIBLE, a.stepsVisibilityForTest());
        assertTrue("headline: " + a.headlineForTest(),
                a.headlineForTest().contains("0.1.0.13"));
    }

    @Test
    @Config(qualifiers = "w360dp-h800dp-port-mdpi")
    public void availableUpdate_phone() throws Exception {
        publishAvailable("0.1.0.13", 13);
        render("updates_available_360x800.png", 360, 800);
    }

    @Test
    @Config(qualifiers = "w1000dp-h700dp-land-mdpi")
    public void nothingOffered_headset() throws Exception {
        UpdatesActivity a = render("updates_none_1000x700.png", 1000, 700);
        assertEquals("no target chip without an offered release",
                View.GONE, a.toVersionChipVisibilityForTest());
        assertEquals("no steps without an offered release",
                View.GONE, a.stepsVisibilityForTest());
    }

    private UpdatesActivity render(String name, int wDp, int hDp) throws Exception {
        UpdateRepositoryProvider.get(UpdateTestFixture.context());
        ActivityController<UpdatesActivity> c = Robolectric.buildActivity(UpdatesActivity.class)
                .create().start().resume().visible();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        UpdatesActivity a = c.get();
        float density = a.getResources().getDisplayMetrics().density;
        int w = Math.round(wDp * density), h = Math.round(hDp * density);
        View root = a.findViewById(android.R.id.content);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.BLACK);
        root.draw(new Canvas(b));
        try (FileOutputStream out = new FileOutputStream(new File(outDir, name))) {
            assertTrue(b.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        return a;
    }

    private void publishAvailable(String version, long sequence) throws Exception {
        byte[] apk = ("apk-" + version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String filename = "vibertemis-quest-preview-" + version + ".apk";
        String url = UpdateManifest.PREFIX + "quest-preview-v" + version + "/" + filename;
        byte[] body = new JSONObject()
                .put("schema", 1)
                .put("channel", "quest-preview")
                .put("sequence", sequence)
                .put("version", version)
                .put("native_protocol", UpdateManifest.PROTOCOL)
                .put("assets", new JSONObject().put("android", new JSONObject()
                        .put("filename", filename)
                        .put("url", url)
                        .put("bytes", 71_848_583L)
                        .put("sha256", UpdateTestFixture.hex(UpdateTestFixture.sha256(apk)))
                        .put("package", UpdateTestFixture.packageName(UpdateTestFixture.context()))
                        .put("version_code", installedVersionCode + 1)
                        .put("signer_sha256", UpdateTestFixture.SIGNER_SHA256)))
                .toString().getBytes("UTF-8");
        java.security.Signature s = java.security.Signature.getInstance("SHA256withRSA");
        s.initSign(keyPair.getPrivate());
        s.update(body);
        byte[] sig = s.sign();
        UpdateRepositoryProvider.get(UpdateTestFixture.context())
                .recordAvailable(UpdateManifest.verify(body, sig, keyPem), body, sig);
    }
}
