package com.rajibtutorial;

import android.app.Application;

public class RtApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
