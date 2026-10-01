package com.vibertemis.quest.update;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Delivers the Android install status to the process-scoped coordinator.
 *
 * <p>The {@code PackageInstaller} callback arrives as a broadcast for one
 * session, and this receiver does nothing but hand it over: the
 * coordinator matches the session identity and decides what the state is.
 * Nothing is launched from here, so a status that arrives while the app is
 * in the background still cannot open the consent screen or an installer.
 */
public final class SessionInstallReceiver extends BroadcastReceiver {

    @Override public void onReceive(Context context, Intent intent) {
        InstallSessionState.acceptStatus(intent);
    }
}
