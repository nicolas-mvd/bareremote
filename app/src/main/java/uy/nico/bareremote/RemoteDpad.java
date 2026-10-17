package uy.nico.bareremote;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;

/** A drawn circle with five real buttons for TalkBack, keyboard focus and long OK. */
final class RemoteDpad extends ViewGroup {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ImageButton[] directions = new ImageButton[4];
    private ImageButton center;
    private ImageButton touchTarget;
    private int directionCount;
    private boolean touchCancelled;

    RemoteDpad(Context context) {
        super(context);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    void addDirection(ImageButton button) {
        if (directionCount >= 4) throw new IllegalStateException("Four directions only");
        button.setBackground(states(0x00000000, 0xff424a44, false));
        button.setImageTintList(ColorStateList.valueOf(0xff9ba59d));
        directions[directionCount++] = button;
        addView(button);
    }

    void setCenter(ImageButton button) {
        center = button;
        button.setBackground(states(0xff454947, 0xff626c65, true));
        addView(button);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int size = Math.min(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(size, size);
        int directionWidth = Math.round(size * .5f);
        int directionDepth = Math.round(size * .3f);
        int centerSize = Math.round(size * .4f);
        for (int i = 0; i < 4; i++) {
            boolean vertical = i % 2 == 0;
            measureChildExact(directions[i], vertical ? directionWidth : directionDepth,
                    vertical ? directionDepth : directionWidth);
        }
        measureChildExact(center, centerSize, centerSize);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int size = right - left;
        for (int i = 0; i < 4; i++) {
            View view = directions[i];
            int x = (size - view.getMeasuredWidth()) / 2;
            int y = (size - view.getMeasuredHeight()) / 2;
            if (i == 0) y = 0;
            if (i == 1) x = size - view.getMeasuredWidth();
            if (i == 2) y = size - view.getMeasuredHeight();
            if (i == 3) x = 0;
            view.layout(x, y, x + view.getMeasuredWidth(), y + view.getMeasuredHeight());
        }
        int inset = (size - center.getMeasuredWidth()) / 2;
        center.layout(inset, inset, inset + center.getMeasuredWidth(), inset + center.getMeasuredHeight());
    }

    @Override protected void onDraw(Canvas canvas) {
        float middle = getWidth() / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xff1c1e1d);
        canvas.drawCircle(middle, middle, middle - dp(1), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1));
        paint.setColor(0xff141615);
        canvas.drawCircle(middle, middle, middle - dp(1), paint);
    }

    // Every part of the round pad belongs to one direction. Keep the five native
    // children for accessibility; only physical touch coordinates need remapping.
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            float x = event.getX() - getWidth() / 2f;
            float y = event.getY() - getHeight() / 2f;
            float distance = (float) Math.sqrt(x * x + y * y);
            if (distance > getWidth() / 2f) return false;
            touchTarget = distance <= getWidth() * .2f ? center
                    : Math.abs(x) > Math.abs(y) ? directions[x > 0 ? 1 : 3]
                    : directions[y > 0 ? 2 : 0];
            if (!touchTarget.isEnabled()) { touchTarget = null; return false; }
            touchCancelled = false;
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (touchTarget == null) return super.dispatchTouchEvent(event);
        float x = event.getX() - getWidth() / 2f;
        float y = event.getY() - getHeight() / 2f;
        if (x * x + y * y > getWidth() * getWidth() / 4f) touchCancelled = true;
        MotionEvent childEvent = MotionEvent.obtain(event);
        childEvent.setLocation(touchTarget.getWidth() / 2f, touchTarget.getHeight() / 2f);
        if (touchCancelled) childEvent.setAction(MotionEvent.ACTION_CANCEL);
        boolean handled = touchTarget.dispatchTouchEvent(childEvent);
        childEvent.recycle();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            touchTarget = null;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return handled || touchCancelled;
    }

    private StateListDrawable states(int normal, int pressed, boolean border) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, circle(pressed, border, false));
        states.addState(new int[] {android.R.attr.state_focused}, circle(normal, border, true));
        states.addState(new int[0], circle(normal, border, false));
        return states;
    }
    private GradientDrawable circle(int fill, boolean border, boolean focus) {
        GradientDrawable result = new GradientDrawable();
        result.setShape(GradientDrawable.OVAL);
        result.setColor(fill);
        if (border || focus) result.setStroke(dp(focus ? 2 : 1), focus ? 0xffdadcd9 : 0xff141615);
        return result;
    }
    private void measureChildExact(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
