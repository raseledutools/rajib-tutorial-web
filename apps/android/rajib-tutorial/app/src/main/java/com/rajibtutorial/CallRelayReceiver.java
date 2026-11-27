package com.rajibtutorial;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.telephony.TelephonyManager;

/**
 * "Phone B" relay: watches normal SIM calls on this phone (only while the relay is switched on) and hands
 * them to {@link RelayCall}, which alerts the owner (Phone A) and carries out A's signed commands.
 */
public class CallRelayReceiver extends BroadcastReceiver {
    static final String PREFS = "rt_tel";
    static final String K_ON = "relay_on", K_TO = "relay_to";

    /** How long to wait for the broadcast that carries the number before reporting "unknown number". */
    private static final long NUMBER_WAIT_MS = 2500;

    @Override
    public void onReceive(Context ctx, Intent i) {
        if (i == null || !TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(i.getAction())) return;
        final Context app = ctx.getApplicationContext();
        if (!RelayCall.enabled(app)) return;
        String state = i.getStringExtra(TelephonyManager.EXTRA_STATE);
        if (TelephonyManager.EXTRA_STATE_RINGING.equals(state)) {
            String raw = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER);
            if (raw != null && !raw.trim().isEmpty()) {
                RelayCall.onRinging(app, raw.trim());
            } else {
                final PendingResult pr = goAsync();
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        // still ringing and nobody reported a number: hidden number or no call-log permission
                        if (RelayCall.phoneRinging(app)) RelayCall.onRinging(app, "");
                    } finally {
                        pr.finish();
                    }
                }, NUMBER_WAIT_MS);
            }
        } else if (TelephonyManager.EXTRA_STATE_OFFHOOK.equals(state)) {
            RelayCall.onOffhook(app);
        } else if (TelephonyManager.EXTRA_STATE_IDLE.equals(state)) {
            RelayCall.onIdle(app);
        }
    }
}
