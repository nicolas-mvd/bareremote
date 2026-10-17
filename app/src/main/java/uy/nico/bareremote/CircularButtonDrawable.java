package uy.nico.bareremote;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Circular face centered in its button's full, accessible rectangular hit area. */
final class CircularButtonDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean pressed, focused;
    private int alpha = 255;

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float radius = Math.min(bounds.width(), bounds.height()) / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(pressed ? 0xff454b47 : 0xff1c1e1d);
        paint.setAlpha(alpha);
        canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius, paint);
        if (focused) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0xffdadcd9);
            paint.setAlpha(alpha);
            paint.setStrokeWidth(2);
            canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), radius - 1, paint);
        }
    }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        boolean nextPressed = false, nextFocused = false;
        for (int state : states) {
            if (state == android.R.attr.state_pressed) nextPressed = true;
            if (state == android.R.attr.state_focused) nextFocused = true;
        }
        if (pressed == nextPressed && focused == nextFocused) return false;
        pressed = nextPressed;
        focused = nextFocused;
        invalidateSelf();
        return true;
    }
    @Override public void setAlpha(int alpha) { this.alpha = alpha; invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
