package com.rajibtutorial;

import android.view.View;

/** One "page" of the native section (home, course, live, manage, members). */
abstract class Screen {
    abstract View build();
    /** Called after data was reloaded or the user/session changed. */
    void refresh() {}
    /** Called when the screen is removed (stop players, etc). */
    void release() {}
    void onPause() {}
    void onResume() {}
    /** Return true if the screen handled the back press (e.g. closed fullscreen). */
    boolean onBack() { return false; }
}
