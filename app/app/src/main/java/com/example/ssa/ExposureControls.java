// SPDX-License-Identifier: MIT
package com.example.ssa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;

import com.example.ssa.databinding.ActivityCapBinding;

// 撮像画面の露光時間の操作をまとめたもの.
// - つまみ: ダイヤル (長押しで出る円いダイヤル) と 目盛り (横にスワイプする帯) を切り替えられる
// - ×2 / ÷2 ボタン (長押しで連続), 値をタップして数値で入力
// - 自動露出: オンにすると試し撮りで露光時間を決める. オンの間は手で動かせず, ISO を変えると測り直す
// - ロック: 露光時間を変えられなくする
public class ExposureControls {

    public interface Host {
        // 露光時間 (ns) が変わった. プレビューに反映する
        void onExposureChanged(long ns);

        // 自動露出を始める. 始められたら true
        boolean startAutoExposure(long startNs, long minNs, long maxNs, Cam.AutoExposureListener listener);

        void cancelAutoExposure();

        void showStatus(Cam.StatusType type, String message);
    }

    private static final String PREFS = "exposure_controls";
    private static final String KEY_MODE = "mode";
    private static final String MODE_DIAL = "dial";
    private static final String MODE_RULER = "ruler";
    private static final double LOG_TWO = Math.log10(2.0);
    private static final long REPEAT_MS = 130;

    private final AppCompatActivity activity;
    private final Host host;
    private final KnobHaptics haptics;
    private final TextView text;
    private final ExposureRulerView ruler;
    private final ExposureDialView dial;
    private final TextView dialPad;
    private final Button halve;
    private final Button twice;
    private final Switch autoSwitch;
    private final AppCompatImageView lock;

    private double log = ExposureScale.toLog(100.0);
    private long ns = 100000000L;
    private boolean auto = false;
    private boolean metering = false;
    private boolean locked = false;
    // 自動露出のスイッチをプログラムから戻すときに, 操作として扱わない
    private boolean ignoreSwitch = false;

    public ExposureControls(AppCompatActivity activity, ActivityCapBinding binding, Host host) {
        this.activity = activity;
        this.host = host;
        haptics = new KnobHaptics(activity);
        text = binding.expoTxt;
        ruler = binding.expoRuler;
        dial = binding.expoDial;
        dialPad = binding.expoDialPad;
        halve = binding.expoHalve;
        twice = binding.expoDouble;
        autoSwitch = binding.expoAuto;
        lock = binding.expoLock;

        ruler.setValue(log);
        dial.setValue(log);
        updateText();

        ruler.setListener(new ExposureKnob.Listener() {
            @Override
            public void onKnobValueChanged(double value) {
                apply(value, ruler);
            }
        });
        dial.setListener(new ExposureKnob.Listener() {
            @Override
            public void onKnobValueChanged(double value) {
                apply(value, dial);
            }
        });
        dial.attachTrigger(dialPad);
        dialPad.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Toast.makeText(ExposureControls.this.activity, R.string.exposure_dial_tap_hint, Toast.LENGTH_SHORT)
                        .show();
            }
        });

        setUpStepButton(halve, -LOG_TWO);
        setUpStepButton(twice, LOG_TWO);

        text.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showInputDialog();
            }
        });

        boolean rulerMode = MODE_RULER.equals(prefs().getString(KEY_MODE, MODE_DIAL));
        (rulerMode ? binding.expoModeRuler : binding.expoModeDial).setChecked(true);
        setRulerMode(rulerMode);
        binding.expoMode.setOnCheckedChangeListener((group, checkedId) -> {
            boolean useRuler = checkedId == R.id.expo_mode_ruler;
            setRulerMode(useRuler);
            prefs().edit().putString(KEY_MODE, useRuler ? MODE_RULER : MODE_DIAL).apply();
        });

        autoSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (ignoreSwitch) {
                    return;
                }
                auto = checked;
                if (checked) {
                    startMetering();
                } else if (metering) {
                    host.cancelAutoExposure();
                }
                updateEnabled();
            }
        });

        lock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                locked = !locked;
                lock.setImageResource(locked ? R.drawable.ic_lock : R.drawable.ic_unlock);
                lock.setAlpha(locked ? 0.5f : 1.0f);
                updateEnabled();
            }
        });
        updateEnabled();
    }

    // 今の露光時間 (ns)
    public long exposureNs() {
        return ns;
    }

    // ISO を変え終わった. 自動露出がオンなら測り直す
    public void onIsoChanged() {
        if (auto && !metering && !locked) {
            startMetering();
        }
    }

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void setRulerMode(boolean useRuler) {
        ruler.setVisibility(useRuler ? View.VISIBLE : View.GONE);
        dialPad.setVisibility(useRuler ? View.GONE : View.VISIBLE);
    }

    // 手で動かせる範囲 (log10 ms). カメラが受け付ける範囲には狭めない
    private double[] range() {
        return new double[] { ExposureScale.LOG_MIN, ExposureScale.LOG_MAX };
    }

    // 露光時間を value (log10 ms) にする. source はそれを動かしたつまみ (なければ null).
    // 動かしている最中のつまみに値を戻すと端の手応えが繰り返し鳴るので, source 以外のつまみだけ合わせる
    private void apply(double value, ExposureKnob source) {
        double[] r = range();
        ruler.setRange(r[0], r[1]);
        dial.setRange(r[0], r[1]);
        log = Math.max(r[0], Math.min(r[1], value));
        if (source != ruler) {
            ruler.setValue(log);
        }
        if (source != dial) {
            dial.setValue(log);
        }
        long next = ExposureScale.toNs(log);
        if (next != ns) {
            ns = next;
            host.onExposureChanged(ns);
        }
        updateText();
    }

    private void updateText() {
        text.setText(activity.getString(R.string.exposure_format, ExposureScale.format(ns / 1000000.0)));
    }

    // ×2 / ÷2. 押すと 1 回, 長押しで連続
    @SuppressLint("ClickableViewAccessibility")
    private void setUpStepButton(final Button button, final double delta) {
        final Runnable repeat = new Runnable() {
            @Override
            public void run() {
                if (step(delta)) {
                    button.postDelayed(this, REPEAT_MS);
                }
            }
        };
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                step(delta);
            }
        });
        button.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                repeat.run();
                return true;
            }
        });
        button.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    button.removeCallbacks(repeat);
                }
                // 押した見た目や click / long click はボタンに任せる
                return false;
            }
        });
    }

    // 1 段動かす. 端でそれ以上動かなければ false
    private boolean step(double delta) {
        if (!halve.isEnabled()) {
            return false;
        }
        long before = ns;
        apply(log + delta, null);
        if (ns == before) {
            haptics.limit();
            return false;
        }
        haptics.step();
        return true;
    }

    private void showInputDialog() {
        double[] r = range();
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setText(ExposureScale.plain(ns / 1000000.0));
        input.setSelectAllOnFocus(true);
        FrameLayout container = new FrameLayout(activity);
        int pad = (int) (20 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(pad, 0, pad, 0);
        container.addView(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.exposure_input_title)
                .setMessage(activity.getString(R.string.exposure_input_message,
                        ExposureScale.format(ExposureScale.roundMs(ExposureScale.toMs(r[0]))),
                        ExposureScale.format(ExposureScale.roundMs(ExposureScale.toMs(r[1])))))
                .setView(container)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> applyInput(input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        input.requestFocus();
    }

    private void applyInput(String s) {
        double ms;
        try {
            // 小数点にコンマを使うキーボードもある
            ms = Double.parseDouble(s.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            ms = Double.NaN;
        }
        if (!(ms > 0) || Double.isInfinite(ms)) {
            Toast.makeText(activity, R.string.exposure_input_invalid, Toast.LENGTH_SHORT).show();
            return;
        }
        double wanted = ExposureScale.roundMs(ms);
        apply(ExposureScale.toLog(ms), null);
        if (Math.abs(ns / 1000000.0 - wanted) > wanted * 1e-6) {
            Toast.makeText(activity, activity.getString(R.string.exposure_input_clamped,
                    ExposureScale.format(ns / 1000000.0)), Toast.LENGTH_SHORT).show();
        }
    }

    private void startMetering() {
        double[] r = range();
        metering = host.startAutoExposure(ns, ExposureScale.toNs(r[0]), ExposureScale.toNs(r[1]),
                new Cam.AutoExposureListener() {
                    @Override
                    public void onAutoExposureShot(long shotNs) {
                        String value = ExposureScale.format(shotNs / 1000000.0);
                        text.setText(activity.getString(R.string.exposure_auto_measuring, value));
                        double shotLog = ExposureScale.nsToLog(shotNs);
                        ruler.setValue(shotLog);
                        dial.setValue(shotLog);
                        host.showStatus(Cam.StatusType.LOADING,
                                activity.getString(R.string.status_auto_exposure_measuring, value));
                    }

                    @Override
                    public void onAutoExposureFinished(AutoExposure.Outcome outcome, long resultNs, double fill) {
                        finishMetering(outcome, resultNs, fill);
                    }
                });
        if (!metering) {
            Toast.makeText(activity, R.string.error_auto_exposure_busy, Toast.LENGTH_LONG).show();
            setAutoSwitch(false);
        }
        updateEnabled();
    }

    private void finishMetering(AutoExposure.Outcome outcome, long resultNs, double fill) {
        metering = false;
        if (outcome == null) {
            // 失敗か中断. 露光時間は元のまま
            apply(log, null);
            setAutoSwitch(false);
            host.showStatus(Cam.StatusType.ERROR, activity.getString(R.string.status_auto_exposure_failed));
            updateEnabled();
            return;
        }
        apply(ExposureScale.nsToLog(resultNs), null);
        // 試し撮りの露光時間は丸めていないので, 丸めた値 (表示している値) をプレビューと撮像に使わせる
        host.onExposureChanged(ns);
        String value = ExposureScale.format(ns / 1000000.0);
        int peak = (int) Math.round(fill * 100);
        switch (outcome) {
            case OK:
                host.showStatus(Cam.StatusType.SUCCESS,
                        activity.getString(R.string.status_auto_exposure_ok, value, peak));
                break;
            case TOO_BRIGHT:
                host.showStatus(Cam.StatusType.ERROR,
                        activity.getString(R.string.status_auto_exposure_too_bright, value));
                break;
            case TOO_DARK:
                host.showStatus(Cam.StatusType.ERROR,
                        activity.getString(R.string.status_auto_exposure_too_dark, value, peak));
                break;
            case NOT_CONVERGED:
                host.showStatus(Cam.StatusType.ERROR,
                        activity.getString(R.string.status_auto_exposure_not_converged, value, peak));
                break;
        }
        updateEnabled();
    }

    private void setAutoSwitch(boolean checked) {
        auto = checked;
        ignoreSwitch = true;
        autoSwitch.setChecked(checked);
        ignoreSwitch = false;
    }

    // 自動露出がオン, またはロック中は手で動かせない
    private void updateEnabled() {
        boolean manual = !auto && !locked;
        ruler.setEnabled(manual);
        dialPad.setEnabled(manual);
        dialPad.setAlpha(manual ? 1.0f : 0.5f);
        halve.setEnabled(manual);
        twice.setEnabled(manual);
        text.setEnabled(manual);
        // 自動露出をやめた後, 今撮っている 1 枚が終わるまではオンに戻せない
        autoSwitch.setEnabled(!locked && !(metering && !auto));
    }
}
