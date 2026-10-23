// SPDX-License-Identifier: MIT
package com.example.ssa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

// 露光時間のダイヤル. 画面全体に重ねておき, 普段は隠しておく
// (GONE だと ConstraintLayout が大きさ 0 にするので INVISIBLE で隠す).
// attachTrigger() で渡したボタンを長押しすると, 指のそばに半透明の円いダイヤルが出て,
// そのまま指をぐるぐる回すと値が変わる (時計回りで長く). 指を離すと消える.
// 1 周で 10 倍. 何周でも回せる
public class ExposureDialView extends View implements ExposureKnob {

    private static final double DECADES_PER_TURN = 1.0;
    // 上 (12 時) の印が今の値
    private static final double POINTER_ANGLE = -Math.PI / 2;
    // 表示する目盛りの範囲 (今の値から ± 何桁か). 0.5 で 1 周なので, 裏で重ならないよう少し狭く
    private static final double VISIBLE_DECADES = 0.46;
    private static final long LONG_PRESS_MS = 280;
    private static final int POINTER_COLOR = 0xFFFFD60A;
    private static final int END_COLOR = 0xFFFF6B4A;

    private final float density;
    private final int touchSlop;
    private final KnobState knob;
    private final KnobHaptics haptics;
    private final Paint diskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path pointerPath = new Path();
    private final int[] location = new int[2];
    private Listener listener;

    // ダイヤルの中心と半径 (このビューの座標)
    private float cx;
    private float cy;
    private float radius;
    private float ringWidth;
    private double prevAngle;
    private boolean hasPrevAngle = false;

    public ExposureDialView(Context context) {
        this(context, null);
    }

    public ExposureDialView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        haptics = new KnobHaptics(context);
        knob = new KnobState(haptics, ExposureScale.toLog(100.0));

        diskPaint.setColor(Color.argb(110, 0, 0, 0));
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(Color.argb(90, 0, 0, 0));
        tickPaint.setColor(Color.WHITE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);
        labelPaint.setColor(Color.WHITE);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(12));
        valuePaint.setColor(Color.WHITE);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(sp(24));
        valuePaint.setTypeface(Typeface.DEFAULT_BOLD);
        valuePaint.setShadowLayer(4 * density, 0, 0, Color.BLACK);
        pointerPaint.setColor(POINTER_COLOR);
        pointerPaint.setStrokeCap(Paint.Cap.ROUND);
        pointerPaint.setStrokeWidth(3 * density);
        setVisibility(INVISIBLE);
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    @Override
    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void setRange(double minLog, double maxLog) {
        if (knob.setRange(minLog, maxLog)) {
            invalidate();
        }
    }

    @Override
    public void setValue(double log) {
        knob.setValue(log);
        invalidate();
    }

    // trigger を長押しするとダイヤルが出るようにする. 短く押したときは trigger の OnClickListener が呼ばれる
    @SuppressLint("ClickableViewAccessibility")
    public void attachTrigger(final View trigger) {
        trigger.setOnTouchListener(new View.OnTouchListener() {
            private float downX;
            private float downY;
            private float lastX;
            private float lastY;
            private boolean grabbed = false;
            private boolean pending = false;

            private final Runnable grab = new Runnable() {
                @Override
                public void run() {
                    pending = false;
                    grabbed = true;
                    trigger.setPressed(false);
                    // ここからは指がどこへ行っても ScrollView に取られないようにする
                    trigger.getParent().requestDisallowInterceptTouchEvent(true);
                    haptics.grab();
                    show(lastX, lastY);
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (!trigger.isEnabled()) {
                            return false;
                        }
                        downX = lastX = event.getRawX();
                        downY = lastY = event.getRawY();
                        grabbed = false;
                        pending = true;
                        trigger.setPressed(true);
                        trigger.postDelayed(grab, LONG_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lastX = event.getRawX();
                        lastY = event.getRawY();
                        if (grabbed) {
                            drag(lastX, lastY);
                        } else if (pending && Math.hypot(lastX - downX, lastY - downY) > touchSlop) {
                            // 長押しの前に動いたらスクロールとみなす
                            cancelPending();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (grabbed) {
                            hide();
                        } else if (pending) {
                            cancelPending();
                            trigger.performClick();
                        }
                        grabbed = false;
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelPending();
                        if (grabbed) {
                            hide();
                        }
                        grabbed = false;
                        return true;
                }
                return false;
            }

            private void cancelPending() {
                pending = false;
                trigger.removeCallbacks(grab);
                trigger.setPressed(false);
            }
        });
    }

    // 指の位置 (画面座標) のそばにダイヤルを出す. 指がちょうどリングの上に来るように置く
    private void show(float rawX, float rawY) {
        getLocationOnScreen(location);
        float fx = rawX - location[0];
        float fy = rawY - location[1];
        int w = getWidth();
        int h = getHeight();
        ringWidth = 56 * density;
        radius = Math.max(96 * density, Math.min(160 * density, Math.min(w, h) * 0.3f));
        float outer = radius + ringWidth / 2 + 8 * density;

        // 指から画面の中央へ向かって半径分ずらした所を中心にする (右側のパネルで押せば, 指はリングの 3 時の位置)
        float dx = w / 2f - fx;
        float dy = h / 2f - fy;
        float len = (float) Math.hypot(dx, dy);
        if (len < 1) {
            dx = -1;
            dy = 0;
            len = 1;
        }
        cx = clampCenter(fx + dx / len * radius, outer, w);
        cy = clampCenter(fy + dy / len * radius, outer, h);
        hasPrevAngle = false;
        updateAngle(fx, fy);

        animate().cancel();
        setPivotX(cx);
        setPivotY(cy);
        setAlpha(0f);
        setScaleX(0.85f);
        setScaleY(0.85f);
        setVisibility(VISIBLE);
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start();
        invalidate();
    }

    private static float clampCenter(float c, float outer, int size) {
        if (size < 2 * outer) {
            return size / 2f;
        }
        return Math.max(outer, Math.min(size - outer, c));
    }

    private void hide() {
        knob.release();
        animate().cancel();
        animate().alpha(0f).scaleX(0.92f).scaleY(0.92f).setDuration(180).withEndAction(new Runnable() {
            @Override
            public void run() {
                setVisibility(INVISIBLE);
            }
        }).start();
    }

    private void drag(float rawX, float rawY) {
        getLocationOnScreen(location);
        double delta = updateAngle(rawX - location[0], rawY - location[1]);
        if (delta == 0 || !knob.moveBy(delta / (2 * Math.PI) * DECADES_PER_TURN)) {
            return;
        }
        invalidate();
        if (listener != null) {
            listener.onKnobValueChanged(knob.value());
        }
    }

    // 指の角度を更新し, 前回からの回転角 (時計回りが正) を返す
    private double updateAngle(float x, float y) {
        // 中心のすぐそばでは角度が暴れるので数えない
        if (Math.hypot(x - cx, y - cy) < 28 * density) {
            hasPrevAngle = false;
            return 0;
        }
        double angle = Math.atan2(y - cy, x - cx);
        double delta = 0;
        if (hasPrevAngle) {
            delta = angle - prevAngle;
            // -π から π に収める (± 180° をまたいだとき)
            if (delta > Math.PI) {
                delta -= 2 * Math.PI;
            } else if (delta < -Math.PI) {
                delta += 2 * Math.PI;
            }
        }
        prevAngle = angle;
        hasPrevAngle = true;
        return delta;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        double value = knob.value();
        float outer = radius + ringWidth / 2;
        float inner = radius - ringWidth / 2;

        // 半透明の円盤とリング
        canvas.drawCircle(cx, cy, outer, diskPaint);
        ringPaint.setStrokeWidth(ringWidth);
        canvas.drawCircle(cx, cy, radius, ringPaint);

        // 目盛り. 時計回りに回すと値が増えるので, 大きい値は印 (12 時) より反時計回りの側にある
        long first = (long) Math.ceil((value - VISIBLE_DECADES) / ExposureScale.DETENT);
        long last = (long) Math.floor((value + VISIBLE_DECADES) / ExposureScale.DETENT);
        for (long i = first; i <= last; i++) {
            double log = i * ExposureScale.DETENT;
            if (log < ExposureScale.LOG_MIN - 1e-9 || log > ExposureScale.LOG_MAX + 1e-9) {
                continue;
            }
            double offset = log - value;
            double angle = angleOf(offset);
            // 裏側ほど薄く
            double d = Math.abs(offset) / VISIBLE_DECADES;
            int alpha = (int) (255 * (1 - 0.8 * d * d));
            boolean major = ExposureScale.isMajorDetent(i);
            double labelMs = ExposureScale.labelMs(i);
            float len = (major ? 20 : (labelMs > 0 ? 14 : 8)) * density;
            float r1 = outer - 6 * density;
            float r0 = r1 - len;
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            tickPaint.setAlpha(alpha);
            tickPaint.setStrokeWidth((major ? 2.6f : 1.5f) * density);
            canvas.drawLine(cx + r0 * cos, cy + r0 * sin, cx + r1 * cos, cy + r1 * sin, tickPaint);
            if (labelMs > 0) {
                float rl = inner + 12 * density;
                labelPaint.setAlpha(alpha);
                labelPaint.setFakeBoldText(major);
                canvas.drawText(ExposureScale.formatShort(labelMs), cx + rl * cos,
                        cy + rl * sin + labelPaint.getTextSize() / 3, labelPaint);
            }
        }
        drawEnd(canvas, knob.minLog() - value, outer, inner);
        drawEnd(canvas, knob.maxLog() - value, outer, inner);

        // 12 時の印 (今の値)
        float tipY = cy - outer + 2 * density;
        pointerPath.reset();
        pointerPath.moveTo(cx, tipY + 4 * density);
        pointerPath.lineTo(cx - 7 * density, tipY - 8 * density);
        pointerPath.lineTo(cx + 7 * density, tipY - 8 * density);
        pointerPath.close();
        pointerPaint.setStyle(Paint.Style.FILL);
        canvas.drawPath(pointerPath, pointerPaint);
        canvas.drawLine(cx, cy - outer + 6 * density, cx, cy - inner, pointerPaint);

        // 中央に今の値
        String text = ExposureScale.format(ExposureScale.roundMs(ExposureScale.toMs(value)));
        canvas.drawText(text, cx, cy + valuePaint.getTextSize() / 3, valuePaint);
    }

    private void drawEnd(Canvas canvas, double offset, float outer, float inner) {
        if (Math.abs(offset) > VISIBLE_DECADES) {
            return;
        }
        double angle = angleOf(offset);
        float cos = (float) Math.cos(angle);
        float sin = (float) Math.sin(angle);
        tickPaint.setColor(END_COLOR);
        tickPaint.setAlpha(230);
        tickPaint.setStrokeWidth(5 * density);
        canvas.drawLine(cx + inner * cos, cy + inner * sin, cx + outer * cos, cy + outer * sin, tickPaint);
        tickPaint.setColor(Color.WHITE);
    }

    private static double angleOf(double offsetDecades) {
        return POINTER_ANGLE - offsetDecades / DECADES_PER_TURN * 2 * Math.PI;
    }
}
