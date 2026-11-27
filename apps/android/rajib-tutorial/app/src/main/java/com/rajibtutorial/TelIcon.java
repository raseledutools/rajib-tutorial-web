package com.rajibtutorial;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Small line icons for the Telephone screens, drawn on a 24x24 grid. */
final class TelIcon extends Drawable {
    static final int BACK = 0, SEARCH = 1, MORE = 2, PHONE = 3, VIDEO = 4, CHAT = 5, CHAT_PLUS = 6, ARCHIVE = 7,
        PEOPLE = 8, SETTINGS = 9, PIN = 10, CLIP = 11, SMILE = 12, MIC = 13, SEND = 14, CLOSE = 15, CHECK = 16,
        CHECK2 = 17, PERSON = 18, TRASH = 19;

    private final int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    TelIcon(int kind, int color) {
        this.kind = kind;
        p.setColor(color);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeWidth(1.9f);
    }

    private void line(Canvas c, float... v) {
        Path q = new Path();
        q.moveTo(v[0], v[1]);
        for (int i = 2; i + 1 < v.length; i += 2) q.lineTo(v[i], v[i + 1]);
        c.drawPath(q, p);
    }

    private void box(Canvas c, float l, float t, float r, float b, float rad) {
        c.drawRoundRect(new RectF(l, t, r, b), rad, rad, p);
    }

    @Override public void draw(Canvas c) {
        float s = Math.min(getBounds().width(), getBounds().height()) / 24f;
        c.save();
        c.translate(getBounds().exactCenterX() - 12 * s, getBounds().exactCenterY() - 12 * s);
        c.scale(s, s);
        p.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case BACK: line(c, 20, 12, 5, 12); line(c, 11, 6, 5, 12, 11, 18); break;
            case SEARCH: c.drawCircle(10.5f, 10.5f, 6, p); line(c, 15, 15, 20, 20); break;
            case MORE:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 5, 1.9f, p); c.drawCircle(12, 12, 1.9f, p); c.drawCircle(12, 19, 1.9f, p);
                break;
            case PHONE: {
                Path q = new Path();
                q.moveTo(5, 4); q.lineTo(9, 4); q.lineTo(10.5f, 8.5f); q.lineTo(8.2f, 10.2f);
                q.cubicTo(9.6f, 13, 11, 14.4f, 13.8f, 15.8f);
                q.lineTo(15.5f, 13.5f); q.lineTo(20, 15); q.lineTo(20, 19);
                q.cubicTo(20, 19.6f, 19.6f, 20, 19, 20);
                q.cubicTo(10.5f, 20, 4, 13.5f, 4, 5);
                q.cubicTo(4, 4.4f, 4.4f, 4, 5, 4);
                q.close();
                c.drawPath(q, p);
                break;
            }
            case VIDEO: box(c, 3, 6.5f, 15, 17.5f, 2.5f); line(c, 15, 10.5f, 21, 7.5f, 21, 16.5f, 15, 13.5f); break;
            case CHAT:
            case CHAT_PLUS:
                line(c, 4, 20, 4, 6, 6, 4, 18, 4, 20, 6, 20, 14, 18, 16, 8, 16, 4, 20);
                if (kind == CHAT) { line(c, 8, 8.5f, 16, 8.5f); line(c, 8, 11.8f, 13, 11.8f); }
                else { line(c, 9, 10, 15, 10); line(c, 12, 7, 12, 13); }
                break;
            case ARCHIVE:
                box(c, 4, 4, 20, 20, 2.5f); line(c, 4, 8.5f, 20, 8.5f);
                line(c, 12, 11, 12, 16.5f); line(c, 9.5f, 14, 12, 16.5f, 14.5f, 14);
                break;
            case PEOPLE:
                c.drawCircle(9, 9, 3, p); c.drawArc(new RectF(3.5f, 14, 14.5f, 25), 180, 180, false, p);
                c.drawCircle(16.5f, 8, 2.3f, p); c.drawArc(new RectF(13, 13, 21.5f, 23), 250, 110, false, p);
                break;
            case SETTINGS:
                line(c, 4, 7, 20, 7); line(c, 4, 17, 20, 17);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(9, 7, 2.8f, p); c.drawCircle(15, 17, 2.8f, p);
                break;
            case PIN: line(c, 8, 4, 16, 4); line(c, 9.5f, 4, 9.5f, 10, 7, 14, 17, 14, 14.5f, 10, 14.5f, 4); line(c, 12, 14, 12, 21); break;
            case CLIP: {
                Path q = new Path();
                q.moveTo(17, 7); q.lineTo(17, 16); q.cubicTo(17, 22.7f, 7, 22.7f, 7, 16);
                q.lineTo(7, 6.5f); q.cubicTo(7, 2, 13.8f, 2, 13.8f, 6.5f);
                q.lineTo(13.8f, 15.5f); q.cubicTo(13.8f, 18, 10.2f, 18, 10.2f, 15.5f); q.lineTo(10.2f, 8);
                c.drawPath(q, p);
                break;
            }
            case SMILE:
                c.drawCircle(12, 12, 8.5f, p); c.drawArc(new RectF(8, 8, 16, 16), 25, 130, false, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(9, 9.8f, 1.2f, p); c.drawCircle(15, 9.8f, 1.2f, p);
                break;
            case MIC:
                box(c, 9, 3, 15, 14, 3); c.drawArc(new RectF(6, 6, 18, 18), 0, 180, false, p);
                line(c, 12, 18, 12, 21);
                break;
            case SEND: {
                p.setStyle(Paint.Style.FILL);
                Path q = new Path();
                q.moveTo(4, 4); q.lineTo(21, 12); q.lineTo(4, 20); q.lineTo(4, 14); q.lineTo(14, 12); q.lineTo(4, 10); q.close();
                c.drawPath(q, p);
                break;
            }
            case CLOSE: line(c, 6, 6, 18, 18); line(c, 18, 6, 6, 18); break;
            case CHECK: line(c, 5, 12.5f, 10, 17.5f, 19.5f, 7.5f); break;
            case CHECK2: line(c, 2, 12.5f, 7, 17.5f, 16.5f, 7.5f); line(c, 11.5f, 16.5f, 12.5f, 17.5f, 22, 7.5f); break;
            case PERSON:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(12, 8, 4.2f, p); c.drawArc(new RectF(4, 14, 20, 28), 180, 180, true, p);
                break;
            case TRASH: line(c, 5, 7, 19, 7); line(c, 9.5f, 7, 10, 4, 14, 4, 14.5f, 7); line(c, 7, 7, 8, 20, 16, 20, 17, 7); break;
            default: break;
        }
        c.restore();
    }

    @Override public void setAlpha(int a) { p.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter f) { p.setColorFilter(f); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
