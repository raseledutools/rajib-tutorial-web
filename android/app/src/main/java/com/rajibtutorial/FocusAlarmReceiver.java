package com.rajibtutorial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class FocusAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        FocusManager fm = new FocusManager(ctx);
        if (!fm.isFocusEnabled()) return;

        // Session শেষ হয়ে গেছে কিনা check করো
        if (!fm.isSessionActive()) {
            fm.setFocusEnabled(false);
            ctx.stopService(new Intent(ctx, FocusOverlayService.class));
        }
    }
}
