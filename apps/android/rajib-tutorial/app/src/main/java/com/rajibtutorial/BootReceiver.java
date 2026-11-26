package com.rajibtutorial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        String act = intent.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(act)) {   // after an app update: re-arm background checks
            UpdateWorker.schedule(ctx);
            PushManager.init(ctx);
            return;
        }
        if (!Intent.ACTION_BOOT_COMPLETED.equals(act)) return;
        UpdateWorker.schedule(ctx);
        AppLock.ensureService(ctx);
        FocusManager fm = new FocusManager(ctx);
        // Reboot এর পর session এখনো active থাকলে service restart করো
        if (fm.isFocusEnabled() && fm.isSessionActive()) {
            Intent svc = new Intent(ctx, FocusOverlayService.class)
                .setAction(FocusOverlayService.ACTION_START);
            ctx.startForegroundService(svc);
        } else {
            // Session expired হলে clear করো
            fm.setFocusEnabled(false);
            fm.clearSession();
        }
    }
}
