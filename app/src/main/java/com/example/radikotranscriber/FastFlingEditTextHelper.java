package com.example.radikotranscriber;

import android.content.Context;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.OverScroller;

/**
 * Adds a long, smooth inertial fling to large editable transcript fields.
 * Slow drags/taps are left to EditText so cursor movement and selection keep working.
 */
public final class FastFlingEditTextHelper {
    private FastFlingEditTextHelper() {}

    public static void attach(EditText edit) {
        if (edit == null) return;
        new Controller(edit).attach();
    }

    private static final class Controller implements View.OnTouchListener, Runnable {
        private static final float VELOCITY_MULTIPLIER = 2.15f;
        private final EditText edit;
        private final OverScroller scroller;
        private final int minFlingVelocity;
        private final int maxFlingVelocity;
        private VelocityTracker velocityTracker;

        Controller(EditText edit) {
            this.edit = edit;
            Context context = edit.getContext();
            scroller = new OverScroller(context);
            scroller.setFriction(ViewConfiguration.getScrollFriction() * 0.58f);
            ViewConfiguration vc = ViewConfiguration.get(context);
            minFlingVelocity = Math.max(vc.getScaledMinimumFlingVelocity() * 4,
                    Math.round(650f * context.getResources().getDisplayMetrics().density));
            maxFlingVelocity = vc.getScaledMaximumFlingVelocity();
        }

        void attach() {
            edit.setVerticalScrollBarEnabled(true);
            edit.setNestedScrollingEnabled(true);
            edit.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
            edit.setOnTouchListener(this);
        }

        @Override public boolean onTouch(View v, MotionEvent event) {
            ViewParent parent = v.getParent();
            int action = event.getActionMasked();

            if (action == MotionEvent.ACTION_DOWN) {
                scroller.forceFinished(true);
                edit.removeCallbacks(this);
                recycleVelocityTracker();
                velocityTracker = VelocityTracker.obtain();
                velocityTracker.addMovement(event);
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return false;
            }

            if (velocityTracker != null) velocityTracker.addMovement(event);

            if (action == MotionEvent.ACTION_MOVE) {
                if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
                return false;
            }

            if (action == MotionEvent.ACTION_UP) {
                boolean flung = false;
                if (velocityTracker != null && maxScrollY() > 0) {
                    velocityTracker.computeCurrentVelocity(1000, maxFlingVelocity);
                    float vy = velocityTracker.getYVelocity();
                    float vx = velocityTracker.getXVelocity();
                    if (Math.abs(vy) >= minFlingVelocity && Math.abs(vy) > Math.abs(vx) * 1.15f) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        edit.onTouchEvent(cancel);
                        cancel.recycle();
                        int scrollVelocity = clamp(Math.round(-vy * VELOCITY_MULTIPLIER),
                                -maxFlingVelocity * 2, maxFlingVelocity * 2);
                        startFling(scrollVelocity);
                        flung = true;
                    }
                }
                recycleVelocityTracker();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
                return flung;
            }

            if (action == MotionEvent.ACTION_CANCEL) {
                recycleVelocityTracker();
                if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            }
            return false;
        }

        private void startFling(int velocityY) {
            int maxY = maxScrollY();
            int startY = clamp(edit.getScrollY(), 0, maxY);
            scroller.forceFinished(true);
            scroller.fling(0, startY, 0, velocityY,
                    0, 0, 0, maxY, 0, Math.max(0, edit.getHeight() / 8));
            edit.removeCallbacks(this);
            edit.postOnAnimation(this);
        }

        @Override public void run() {
            if (!scroller.computeScrollOffset()) return;
            int maxY = maxScrollY();
            int y = clamp(scroller.getCurrY(), 0, maxY);
            edit.scrollTo(edit.getScrollX(), y);
            if (!scroller.isFinished() && y > 0 && y < maxY) edit.postOnAnimation(this);
            else scroller.forceFinished(true);
        }

        private int maxScrollY() {
            Layout layout = edit.getLayout();
            if (layout == null) return 0;
            int viewport = Math.max(1,
                    edit.getHeight() - edit.getTotalPaddingTop() - edit.getTotalPaddingBottom());
            return Math.max(0, layout.getHeight() - viewport);
        }

        private void recycleVelocityTracker() {
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }

        private static int clamp(int value, int min, int max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}
