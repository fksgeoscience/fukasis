// SPDX-License-Identifier: MIT
package com.example.ssa;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

// つまみを回したときの手応え. 目盛りごとの「カチッ」, 10 倍ごとの強めの「カチッ」, 端に当たったときの「ゴツッ」.
// 速く回すほど 1 回 1 回は軽く, 詰まりすぎた分は省いて, 本物のつまみのような細かい振動にする
public class KnobHaptics {

    // これより短い間隔の目盛りは省く (振動が重なって, ただのブーンになるため)
    private static final long MIN_INTERVAL_MS = 22;
    // 目盛りを 1 秒にこれ以上越えたら「速い」とみなして軽くする
    private static final double FAST_DETENTS_PER_SEC = 12.0;

    private final Vibrator vibrator;
    private final boolean hasPrimitives;
    private final boolean hasLowTick;
    private final boolean hasThud;
    private long lastDetentAt = 0;

    public KnobHaptics(Context context) {
        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = context.getSystemService(VibratorManager.class);
            v = manager != null ? manager.getDefaultVibrator() : null;
        } else {
            v = context.getSystemService(Vibrator.class);
        }
        vibrator = (v != null && v.hasVibrator()) ? v : null;

        boolean primitives = false;
        boolean lowTick = false;
        boolean thud = false;
        if (vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            primitives = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_TICK,
                    VibrationEffect.Composition.PRIMITIVE_CLICK);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                lowTick = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK);
                thud = vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_THUD);
            }
        }
        hasPrimitives = primitives;
        hasLowTick = lowTick;
        hasThud = thud;
    }

    // 目盛りを越えた. major は 10 倍ごとの目盛り
    public void detent(boolean major) {
        if (vibrator == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        long interval = now - lastDetentAt;
        if (interval < MIN_INTERVAL_MS && !major) {
            return;
        }
        lastDetentAt = now;
        boolean fast = interval < 1000.0 / FAST_DETENTS_PER_SEC;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPrimitives) {
            VibrationEffect.Composition c = VibrationEffect.startComposition();
            if (major) {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, fast ? 0.6f : 1.0f);
            } else if (fast && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasLowTick) {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.5f);
            } else {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, fast ? 0.4f : 0.8f);
            }
            vibrate(c.compose());
        } else {
            vibrate(VibrationEffect.createPredefined(major ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
        }
    }

    // 範囲の端に当たった
    public void limit() {
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPrimitives) {
            VibrationEffect.Composition c = VibrationEffect.startComposition();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && hasThud) {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f);
            } else {
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f);
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 40);
            }
            vibrate(c.compose());
        } else {
            vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        }
    }

    // 長押しでダイヤルが出た
    public void grab() {
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPrimitives) {
            vibrate(VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
                    .compose());
        } else {
            vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        }
    }

    // ×2 / ÷2 ボタン
    public void step() {
        if (vibrator == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasPrimitives) {
            vibrate(VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f)
                    .compose());
        } else {
            vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK));
        }
    }

    private void vibrate(VibrationEffect effect) {
        // 端末の「タップ時の振動」の設定に従わせる
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
        } else {
            vibrator.vibrate(effect, new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        }
    }
}
