package com.rajibtutorial;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * Per-lesson offline indicator, same look as the exe's lesson list:
 *   cloud-download outline  = not saved (tap to save)
 *   progress ring + percent = downloading (tap to cancel)
 *   green circle + check    = saved, plays without internet (tap to remove)
 */
final class OffIcon extends View {
    static final int NONE = 0, BUSY = 1, DONE = 2;
    private static final int GREEN = 0xFF16A34A, TRACK = 0xFFD1D5DB;

    private int state = NONE, pct = 0;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path check = new Path();
    private final Drawable cloud;
    private final long t0 = System.currentTimeMillis();

    OffIcon(Context c) {
        super(c);
        cloud = getResources().getDrawable(R.drawable.ic_off_cloud, null).mutate();
        cloud.setTint(Ui.SUB);
        setClickable(true);
        Ui.pressFx(this);
    }

    /** Reads the current state of one lesson from VideoCache and redraws. */
    void bind(Context ctx, String videoId) {
        int s = NONE, v = 0;
        if (VideoCache.isDownloading(videoId)) { s = BUSY; v = VideoCache.getProgress(videoId); }
        else if (VideoCache.isDownloaded(ctx, videoId)) s = DONE;
        if (s != state || v != pct) { state = s; pct = v; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas cv) {
        float w = getWidth(), h = getHeight();
        float k = Math.min(w, h) / 28f;                 // the exe icon is drawn on a 28x28 grid
        float cx = w / 2f, cy = h / 2f;
        float ox = cx - 14f * k, oy = cy - 14f * k;

        if (state == DONE) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(GREEN);
            cv.drawCircle(cx, cy, 12f * k, p);
            check.reset();
            check.moveTo(ox + 8.5f * k, oy + 14.5f * k);
            check.rLineTo(3.8f * k, 3.8f * k);
            check.rLineTo(7.2f * k, -7.6f * k);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(0xFFFFFFFF);
            p.setStrokeWidth(2.6f * k);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            cv.drawPath(check, p);
        } else if (state == BUSY) {
            rect.set(cx - 11f * k, cy - 11f * k, cx + 11f * k, cy + 11f * k);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(3f * k);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setColor(TRACK);
            cv.drawCircle(cx, cy, 11f * k, p);
            p.setColor(Ui.ACCENT);
            if (pct > 0) {
                cv.drawArc(rect, -90, Math.min(100, pct) * 3.6f, false, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(Typeface.DEFAULT_BOLD);
                p.setTextSize(8.5f * k);
                cv.drawText(String.valueOf(Math.min(100, pct)), cx, cy + 3f * k, p);
            } else {                                     // size unknown / still preparing: spinning arc
                float rot = ((System.currentTimeMillis() - t0) % 900) / 900f * 360f;
                cv.drawArc(rect, rot - 90, 100, false, p);
                postInvalidateOnAnimation();
            }
        } else {
            int s = Math.round(28f * k);
            cloud.setBounds(Math.round(ox), Math.round(oy), Math.round(ox) + s, Math.round(oy) + s);
            cloud.draw(cv);
        }
    }
}
