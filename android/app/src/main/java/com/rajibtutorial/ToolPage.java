package com.rajibtutorial;

import android.view.View;

/** One native Study Tools screen (merge, split, QR ...). */
abstract class ToolPage {
    final StudyToolsActivity a;

    ToolPage(StudyToolsActivity a) { this.a = a; }

    abstract String title();

    /** Scrollable content. */
    abstract View body();

    /** Optional fixed bottom action bar. */
    View footer() { return null; }

    /** Return true if the page consumed the back press (e.g. closed the camera). */
    boolean onBack() { return false; }

    void onPause() { }

    void onDestroy() { }
}
