package uy.nico.bareremote;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;

/** Native control groups, sized once per layout. Extra height becomes breathing room. */
final class RemoteSurface extends ViewGroup {
    private View header, status, pad, keyboard, navigation, volume;
    private int headerHeight, statusHeight, padSize, keyboardHeight, navigationHeight, volumeHeight;
    private int viewportHeight;

    RemoteSurface(Context context) { super(context); }
    void setViewportHeight(int height) { viewportHeight = height; }
    void setHeader(View view) { header = view; addView(view); }
    void setStatus(View view) { status = view; addView(view); }
    void setPad(View view) { pad = view; addView(view); }
    void setKeyboard(View view) { keyboard = view; addView(view); }
    void setNavigation(View view) { navigation = view; addView(view); }
    void setVolume(View view) { volume = view; addView(view); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int controlsWidth = Math.min(width, dp(430));
        headerHeight = dp(48);
        keyboardHeight = dp(48);
        volumeHeight = dp(56);
        navigationHeight = Math.max(dp(64), Math.min(dp(88), Math.round(controlsWidth * .22f)));
        measureExactly(header, controlsWidth, headerHeight);
        status.measure(MeasureSpec.makeMeasureSpec(controlsWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        statusHeight = status.getMeasuredHeight();
        int fixed = headerHeight + statusHeight + keyboardHeight + navigationHeight + volumeHeight + dp(58);
        int preferredPad = Math.min(dp(384), Math.round(controlsWidth * .92f));
        // ScrollView first measures its child with an unconstrained height. Its
        // actual viewport is supplied separately so error text can shrink the
        // pad rather than push the volume row below the visible screen.
        padSize = viewportHeight > 0
                ? Math.min(preferredPad, Math.max(dp(180), viewportHeight - fixed))
                : preferredPad;
        measureExactly(pad, padSize, padSize);
        measureExactly(keyboard, keyboardHeight, keyboardHeight);
        measureExactly(navigation, controlsWidth, navigationHeight);
        measureExactly(volume, controlsWidth, volumeHeight);
        int minimum = headerHeight + statusHeight + padSize + keyboardHeight + navigationHeight
                + volumeHeight + dp(58);
        int height = Math.max(minimum, viewportHeight);
        if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED)
            height = Math.max(minimum, MeasureSpec.getSize(heightSpec));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        int fixed = headerHeight + statusHeight + padSize + keyboardHeight + navigationHeight + volumeHeight + dp(58);
        int free = Math.max(0, height - fixed);
        center(header, width, 0);
        center(status, width, headerHeight);
        center(pad, width, headerHeight + statusHeight + dp(20) + Math.round(free * .45f));
        center(navigation, width, height - volumeHeight - dp(18) - navigationHeight);
        center(keyboard, width, (pad.getBottom() + navigation.getTop() - keyboardHeight) / 2);
        center(volume, width, height - volumeHeight);
    }

    private void center(View view, int width, int top) {
        int left = (width - view.getMeasuredWidth()) / 2;
        view.layout(left, top, left + view.getMeasuredWidth(), top + view.getMeasuredHeight());
    }
    private void measureExactly(View view, int width, int height) {
        view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}

/** Supplies the scroll viewport before Android's unconstrained child measure. */
final class RemoteScrollView extends ScrollView {
    RemoteScrollView(Context context) { super(context); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (getChildCount() > 0 && getChildAt(0) instanceof RemoteSurface) {
            int height = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED ? 0
                    : Math.max(0, MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
            ((RemoteSurface) getChildAt(0)).setViewportHeight(height);
        }
        super.onMeasure(widthSpec, heightSpec);
    }
}
