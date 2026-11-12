package com.rajibtutorial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
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
