package com.rajibtutorial;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** ImageView with pinch-zoom, drag and double-tap zoom. */
final class ZoomImageView extends ImageView {
    private final Matrix m = new Matrix();
    private final float[] v = new float[9];
    private float minS = 1f;
    private final ScaleGestureDetector sgd;
    private final GestureDetector gd;
    private float lx, ly;

    ZoomImageView(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        sgd = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) {
                float cur = scale();
                float target = Math.max(minS, Math.min(minS * 6f, cur * d.getScaleFactor()));
                float f = target / cur;
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                fix();
                setImageMatrix(m);
                return true;
            }
        });
        gd = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) { return true; }
            @Override public boolean onDoubleTap(MotionEvent e) {
                if (scale() > minS * 1.05f) {
                    fit();
                } else {
                    float f = minS * 2.5f / scale();
                    m.postScale(f, f, e.getX(), e.getY());
                    fix();
                    setImageMatrix(m);
                }
                return true;
            }
        });
    }

    private float scale() {
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** Fit the whole image inside the view, centred. */
    void fit() {
        Drawable d = getDrawable();
        if (d == null || getWidth() == 0 || getHeight() == 0) return;
        float dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
        minS = Math.min(getWidth() / dw, getHeight() / dh);
        m.reset();
        m.postScale(minS, minS);
        m.postTranslate((getWidth() - dw * minS) / 2f, (getHeight() - dh * minS) / 2f);
        setImageMatrix(m);
    }

    private void fix() {
        Drawable d = getDrawable();
        if (d == null) return;
        m.getValues(v);
        float s = v[Matrix.MSCALE_X];
        float w = d.getIntrinsicWidth() * s, h = d.getIntrinsicHeight() * s;
        float tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y];
        float nx = w <= getWidth() ? (getWidth() - w) / 2f : Math.max(getWidth() - w, Math.min(0f, tx));
        float ny = h <= getHeight() ? (getHeight() - h) / 2f : Math.max(getHeight() - h, Math.min(0f, ty));
        m.postTranslate(nx - tx, ny - ty);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        fit();
    }

    @Override public void setImageBitmap(android.graphics.Bitmap bm) {
        super.setImageBitmap(bm);
        fit();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        sgd.onTouchEvent(e);
        gd.onTouchEvent(e);
        if (!sgd.isInProgress() && e.getPointerCount() == 1) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: lx = e.getX(); ly = e.getY(); break;
                case MotionEvent.ACTION_MOVE:
                    if (scale() > minS * 1.01f) {
                        m.postTranslate(e.getX() - lx, e.getY() - ly);
                        fix();
                        setImageMatrix(m);
                    }
                    lx = e.getX(); ly = e.getY();
                    break;
                default: break;
            }
        } else if (e.getPointerCount() > 1) {
            lx = e.getX(); ly = e.getY();
        }
        return true;
    }
}
