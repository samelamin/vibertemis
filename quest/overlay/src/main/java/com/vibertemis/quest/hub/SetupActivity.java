package com.vibertemis.quest.hub;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.limelight.R;

/**
 * Offline setup screen. Sectioned walkthrough for both Screen gaming and
 * PCVR, plus offline numbered instructions and clickable HTTPS links. If
 * no browser is registered (Meta Horizon OS panels from a sideloaded
 * build sometimes lack one), the Copy-link action still works.
 *
 * <p>The layout is built programmatically so the activity does not
 * depend on any layout XML beyond the launch hub. The Back control is a
 * fixed button pinned above the scroll body so it is always reachable.
 * Link rows are stacked vertically with a labelled Open button and a
 * Copy button; the label sits above the buttons so a 360dp wide column
 * at fontScale 1.6 still fits.
 */
public class SetupActivity extends Activity {

    private static final String TAG = "SetupActivity";

    private static final Link[] LINKS = new Link[] {
            new Link(R.string.setup_url_alvr_windows,
                    "https://github.com/samelamin/vibertemis/releases/tag/quest-preview-v0.1.0.8"),
            new Link(R.string.setup_url_pcvr_guide,
                    "https://github.com/samelamin/vibertemis/blob/quest-preview-v0.1.0.8/quest/README.md"),
            new Link(R.string.setup_url_sunshine,
                    "https://github.com/LizardByte/Sunshine/releases"),
            new Link(R.string.setup_url_apollo,
                    "https://github.com/ClassicOldSong/Apollo"),
            new Link(R.string.setup_url_requirements,
                    "https://github.com/alvr-org/ALVR/wiki/Requirements"),
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        Button back = new Button(this);
        back.setText(R.string.back_row_title);
        back.setContentDescription(getString(R.string.back_row_title));
        back.setMinHeight(dp(56));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        backLp.bottomMargin = dp(8);
        root.addView(back, backLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout scrollBody = new LinearLayout(this);
        scrollBody.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        scrollBody.setPadding(pad, pad, pad, pad);
        scroll.addView(scrollBody);

        TextView title = makeText(getString(R.string.setup_title), 22, true);
        scrollBody.addView(title, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView intro = makeText(getString(R.string.setup_intro), 16, false);
        scrollBody.addView(intro, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        addSpacer(scrollBody, 12);

        addScreenGamingSection(scrollBody);
        addSpacer(scrollBody, 12);
        addPcvrSection(scrollBody);
        addSpacer(scrollBody, 12);

        TextView linksHead = makeSectionHead(getString(R.string.setup_downloads_title));
        scrollBody.addView(linksHead);
        for (Link link : LINKS) {
            scrollBody.addView(makeLinkRow(link));
        }

        // height=0 weight=1: the scroll body claims all remaining
        // vertical space below the fixed Back button, so it always
        // reaches the viewport bottom at any fontScale / orientation
        // (a MATCH_PARENT ScrollView here can extend below the
        // viewport on small screens).
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f);
        root.addView(scroll, scrollLp);
        setContentView(root);
    }

    private void addScreenGamingSection(LinearLayout parent) {
        TextView head = makeSectionHead(getString(R.string.setup_screen_mode_title));
        parent.addView(head);
        TextView body = makeText(getString(R.string.setup_screen_mode_body), 16, false);
        parent.addView(body, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView pcHead = makeText(getString(R.string.setup_screen_mode_steps_pc), 16, true);
        parent.addView(pcHead);
        addBullet(parent, R.string.setup_screen_mode_step1);
        addBullet(parent, R.string.setup_screen_mode_step2);

        TextView hsHead = makeText(getString(R.string.setup_screen_mode_steps_headset), 16, true);
        parent.addView(hsHead);
        addBullet(parent, R.string.setup_screen_mode_headset_step1);
        addBullet(parent, R.string.setup_screen_mode_headset_step2);
        addBullet(parent, R.string.setup_screen_mode_headset_step3);
        addBullet(parent, R.string.setup_screen_mode_headset_step4);
    }

    private void addPcvrSection(LinearLayout parent) {
        TextView head = makeSectionHead(getString(R.string.setup_pcvr_mode_title));
        parent.addView(head);
        TextView body = makeText(getString(R.string.setup_pcvr_mode_body), 16, false);
        parent.addView(body, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView pcHead = makeText(getString(R.string.setup_pcvr_steps_pc), 16, true);
        parent.addView(pcHead);
        addBullet(parent, R.string.setup_pcvr_step1);
        addBullet(parent, R.string.setup_pcvr_step2);
        addBullet(parent, R.string.setup_pcvr_step3);
        addBullet(parent, R.string.setup_pcvr_step4);

        TextView hsHead = makeText(getString(R.string.setup_pcvr_steps_headset), 16, true);
        parent.addView(hsHead);
        addBullet(parent, R.string.setup_pcvr_headset_step1);
        addBullet(parent, R.string.setup_pcvr_headset_step2);
        addBullet(parent, R.string.setup_pcvr_headset_step3);

        TextView mic = makeText(getString(R.string.setup_pcvr_mic_note), 16, false);
        parent.addView(mic, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView wan = makeText(getString(R.string.setup_pcvr_wan_note), 16, false);
        parent.addView(wan, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView tuning = makeText(getString(R.string.setup_pcvr_tuning_note), 16, false);
        parent.addView(tuning, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView trouble = makeText(getString(R.string.setup_pcvr_troubleshoot_note), 16, false);
        parent.addView(trouble, llParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView makeText(CharSequence text, float sizeSp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private TextView makeSectionHead(CharSequence text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = llParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        lp.bottomMargin = dp(4);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void addBullet(LinearLayout root, int stringId) {
        TextView tv = new TextView(this);
        tv.setText(getString(stringId));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams lp = llParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        root.addView(tv, lp);
    }

    private void addSpacer(LinearLayout root, int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(heightDp)));
        root.addView(v);
    }

    /**
     * Stack label above a full-width Open row and a Copy button. At
     * fontScale 1.6 and a 360dp column, label+button+button in one row
     * crowds; the stacked layout is reliably readable and reachable.
     */
    private View makeLinkRow(final Link link) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnLp = llParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        columnLp.topMargin = dp(8);
        column.setLayoutParams(columnLp);

        TextView label = new TextView(this);
        label.setText(getString(link.titleResId));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setMovementMethod(LinkMovementMethod.getInstance());
        label.setLayoutParams(llParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        column.addView(label);

        Button open = new Button(this);
        open.setText(R.string.setup_open_link);
        open.setContentDescription(getString(R.string.setup_open_link) + " "
                + getString(link.titleResId));
        open.setMinHeight(dp(56));
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        openLp.topMargin = dp(4);
        open.setLayoutParams(openLp);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openLink(link);
            }
        });
        column.addView(open);

        Button copy = new Button(this);
        copy.setText(R.string.setup_copy_link);
        copy.setContentDescription(getString(R.string.setup_copy_link) + " "
                + getString(link.titleResId));
        copy.setMinHeight(dp(56));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.topMargin = dp(4);
        copy.setLayoutParams(copyLp);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (copyLink(link)) {
                    toastCopied();
                }
            }
        });
        column.addView(copy);

        return column;
    }

    /**
     * Direct ACTION_VIEW dispatch. On the two known failure modes
     * (ActivityNotFoundException / SecurityException) we fall back
     * to the clipboard and show ONE toast that explains both the
     * fallback and that the copy happened. If the clipboard copy
     * itself fails, we do NOT claim success: the user sees the
     * accurate copy-failed toast.
     */
    private void openLink(final Link link) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(link.url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (android.content.ActivityNotFoundException e) {
            Log.w(TAG, "No browser for " + link.url + "; falling back to clipboard");
            if (!copyLink(link)) {
                return;
            }
            Toast.makeText(this, R.string.setup_link_no_browser,
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Log.w(TAG, "Browser dispatch security exception: " + e.getMessage());
            if (!copyLink(link)) {
                return;
            }
            Toast.makeText(this, R.string.setup_link_no_browser,
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Copy the URL to the system clipboard. Returns true if the
     * clipboard now holds the URL; false if the clipboard service is
     * missing. The caller decides which toast (copied-success vs
     * copy-failed) to show so the two flows (Open-fallback vs direct
     * Copy-button tap) each get a single accurate toast and never a
     * false "copied" message.
     */
    private boolean copyLink(final Link link) {
        ClipboardManager cm = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            // Do NOT claim the link was copied when the clipboard
            // service is missing.
            Toast.makeText(this,
                    R.string.setup_link_copy_failed,
                    Toast.LENGTH_LONG).show();
            return false;
        }
        cm.setPrimaryClip(ClipData.newPlainText("url", link.url));
        return true;
    }

    private void toastCopied() {
        Toast.makeText(this, R.string.setup_link_copied,
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int dp) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams llParams(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    private static final class Link {
        final int titleResId;
        final String url;
        Link(int titleResId, String url) {
            this.titleResId = titleResId;
            this.url = url;
        }
    }
}