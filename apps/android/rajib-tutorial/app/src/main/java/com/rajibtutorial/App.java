package com.rajibtutorial;

import android.app.Application;

/** Initialises the Firestore failover state (Fs) before any screen / service uses it. */
public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Fs.init(this);
    }
}
