// SPDX-License-Identifier: MIT
package com.example.ssa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.OverScroller;

import com.google.android.material.color.MaterialColors;

// 露光時間の目盛り. 計算尺のような対数の帯を左右にスワイプして動かす. 離すと慣性で流れる.
// 中央の線が今の値
public class ExposureRulerView extends View implements ExposureKnob {

    // 10 倍あたりの幅. 幅が狭いときは, 10 倍分が帯の 9 割に収まるまで縮める (MIN_DECADE_DP まで)
    private static final float DECADE_DP = 180f;
    private static final float MIN_DECADE_DP = 120f;

    private float pxPerDecade;
    private final float density;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint indicatorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();
    private final OverScroller scroller;
    private final int touchSlop;
    private final int minFlingVelocity;
    private final KnobState knob;
    private VelocityTracker velocityTracker;
    private Listener listener;

    private float downX;
    private float downY;
    private float lastX;
    private boolean dragging = false;

    public ExposureRulerView(Context context) {
        this(context, null);
    }

    public ExposureRulerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        pxPerDecade = DECADE_DP * density;
        scroller = new OverScroller(context);
        ViewConfiguration vc = ViewConfiguration.get(context);
        touchSlop = vc.getScaledTouchSlop();
        minFlingVelocity = vc.getScaledMinimumFlingVelocity();
        knob = new KnobState(new KnobHaptics(context), ExposureScale.toLog(100.0));

        int onSurface = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.WHITE);
        int primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary, Color.YELLOW);
        tickPaint.setColor(onSurface);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setColor(onSurface);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11,
                getResources().getDisplayMetrics()));
        indicatorPaint.setColor(primary);
        indicatorPaint.setStrokeWidth(3 * density);
        indicatorPaint.setStrokeCap(Paint.Cap.ROUND);
        // ダイヤルを出すボタン (bg_exposure_dial_pad) と同じく, 文字色をうっすら敷く
        bgPaint.setColor(onSurface);
        updateDescription();
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setRange(double minLog, double maxLog) {
        if (knob.setRange(minLog, maxLog)) {
            updateDescription();
            invalidate();
        }
    }

    @Override
    public void setValue(double log) {
        scroller.forceFinished(true);
        knob.setValue(log);
        updateDescription();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        scroller.forceFinished(true);
        pxPerDecade = Math.max(MIN_DECADE_DP * density, Math.min(DECADE_DP * density, w * 0.9f));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = resolveSize((int) (64 * density), heightMeasureSpec);
        setMeasuredDimension(getDefaultSize(getSuggestedMinimumWidth(), widthMeasureSpec), h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float cx = w / 2f;
        double value = knob.value();
        float radius = 12 * density;
        bgRect.set(0, 0, w, h);
        bgPaint.setAlpha(isEnabled() ? 23 : 10);
        canvas.drawRoundRect(bgRect, radius, radius, bgPaint);

        canvas.save();
        canvas.clipRect(bgRect);
        float baseY = h * 0.6f;
        double halfDecades = cx / pxPerDecade;
        long first = (long) Math.ceil((value - halfDecades) / ExposureScale.DETENT);
        long last = (long) Math.floor((value + halfDecades) / ExposureScale.DETENT);
        int enabledAlpha = isEnabled() ? 255 : 90;
        for (long i = first; i <= last; i++) {
            double log = i * ExposureScale.DETENT;
            if (log < ExposureScale.LOG_MIN - 1e-9 || log > ExposureScale.LOG_MAX + 1e-9) {
                continue;
            }
            float x = (float) (cx + (log - value) * pxPerDecade);
            // 端に行くほど薄く (帯が奥へ回り込んでいるように見せる)
            float d = Math.abs(x - cx) / cx;
            int alpha = (int) (enabledAlpha * (1f - 0.85f * d * d));
            boolean major = ExposureScale.isMajorDetent(i);
            double labelMs = ExposureScale.labelMs(i);
            float len = major ? h * 0.4f : (labelMs > 0 ? h * 0.28f : h * 0.16f);
            tickPaint.setAlpha(alpha);
            tickPaint.setStrokeWidth((major ? 2.2f : 1.3f) * density);
            canvas.drawLine(x, baseY - len, x, baseY, tickPaint);
            if (labelMs > 0) {
                labelPaint.setAlpha(alpha);
                labelPaint.setFakeBoldText(major);
                canvas.drawText(ExposureScale.formatShort(labelMs), x, baseY + labelPaint.getTextSize() + 3 * density,
                        labelPaint);
            }
        }
        // 端の印 (これ以上は動かない)
        drawEnd(canvas, knob.minLog(), cx, h);
        drawEnd(canvas, knob.maxLog(), cx, h);
        canvas.restore();

        // 中央の線 (今の値)
        indicatorPaint.setAlpha(enabledAlpha);
        canvas.drawLine(cx, h * 0.08f, cx, h * 0.92f, indicatorPaint);
    }

    private void drawEnd(Canvas canvas, double log, float cx, int h) {
        float x = (float) (cx + (log - knob.value()) * pxPerDecade);
        if (x < 0 || x > getWidth()) {
            return;
        }
        tickPaint.setAlpha(isEnabled() ? 160 : 60);
        tickPaint.setStrokeWidth(4 * density);
        canvas.drawLine(x, h * 0.15f, x, h * 0.85f, tickPaint);
    }

    private void moveBy(double delta) {
        if (!knob.moveBy(delta)) {
            return;
        }
        updateDescription();
        invalidate();
        if (listener != null) {
            listener.onKnobValueChanged(knob.value());
        }
    }

    private void updateDescription() {
        setContentDescription(ExposureScale.format(ExposureScale.roundMs(ExposureScale.toMs(knob.value()))));
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        if (velocityTracker == null) {
            velocityTracker = VelocityTracker.obtain();
        }
        velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.forceFinished(true);
                downX = event.getX();
                downY = event.getY();
                lastX = downX;
                dragging = false;
                knob.release();
                return true;
            case MotionEvent.ACTION_MOVE: {
                float x = event.getX();
                float dx = Math.abs(x - downX);
                if (!dragging && dx > touchSlop && dx > Math.abs(event.getY() - downY)) {
                    dragging = true;
                    lastX = x;
                    // 横に動かし始めたら, 縦の ScrollView に取られないようにする
                    getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) {
                    // 帯を左へ送ると値が増える
                    moveBy(-(x - lastX) / pxPerDecade);
                    lastX = x;
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    velocityTracker.computeCurrentVelocity(1000);
                    startFling(velocityTracker.getXVelocity());
                } else {
                    performClick();
                }
                recycleTracker();
                return true;
            case MotionEvent.ACTION_CANCEL:
                recycleTracker();
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void recycleTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        dragging = false;
    }

    private void startFling(float vx) {
        if (Math.abs(vx) < minFlingVelocity) {
            return;
        }
        // 位置を px で扱う
        int pos = (int) Math.round(knob.value() * pxPerDecade);
        int min = (int) Math.ceil(knob.minLog() * pxPerDecade);
        int max = (int) Math.floor(knob.maxLog() * pxPerDecade);
        knob.release();
        scroller.fling(pos, 0, (int) -vx, 0, min, max, 0, 0);
        postInvalidateOnAnimation();
    }

    @Override
    public void computeScroll() {
        if (!scroller.computeScrollOffset()) {
            return;
        }
        int x = scroller.getCurrX();
        moveBy(x / (double) pxPerDecade - knob.value());
        boolean hitEnd = x <= Math.ceil(knob.minLog() * pxPerDecade) || x >= Math.floor(knob.maxLog() * pxPerDecade);
        if (hitEnd) {
            // 勢いよく端にぶつかった
            scroller.forceFinished(true);
            knob.hitLimit();
        } else {
            postInvalidateOnAnimation();
        }
    }
}
