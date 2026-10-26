// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.net.Uri;
import android.util.Log;
import android.os.Bundle;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.example.ssa.databinding.ActivityCapBinding;
import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.content.ContentValues;
import android.content.ContentResolver;
import android.provider.MediaStore;
import java.io.OutputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class CapActivity extends AppCompatActivity {

    private static final String TAG = "Camera2Native";
    private CameraDevice camDev;
    private CaptureRequest.Builder capRequestBuilder;
    private CameraCaptureSession capSession;
    private CameraCharacteristics camCharacteristics;
    private static final int CAMERA_PERMISSION_REQUEST_CODE = 100;

    private SoundPool soundPool;
    int alarmSound;
    int shatterSound;
    private long expo = 100000000; // ns
    private int iso = 3200; // iso
    private float fd = 1; // m?
    private int zoom = 0; // 0:noZoom 1:focusing 2:pointing
    private boolean isLine = true;
    private boolean isFocusLocked = false;
    private boolean isIsoLocked = false;
    private boolean isLineLocked = false;

    private TextureView tv1;
    private TextureView tv2;
    private TextView indicator;
    private AppCompatImageView captureStatusIcon;
    private FrameLayout line;
    private EditText nameText;
    private EditText qtyText;
    private ImageReader rawImgReader;

    private String camId = "0";
    private ActivityCapBinding binding;

    private Cam cam;
    private ExposureControls exposureControls;

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) {
            return;
        }
        // 許可された場合は, この後の onResume でカメラを開く
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "カメラの権限がないため撮影できません", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 長時間撮影で画面が消えないようにするため
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        binding = ActivityCapBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        // プレビューとガイド線の位置は画面の左上が基準なので, 画面全体はずらさず, 操作パネルだけに余白を付ける
        SystemBars.pad(binding.getRoot(), binding.controls,
                SystemBars.TOP | SystemBars.RIGHT | SystemBars.BOTTOM, true);

        // setContentView(R.layout.activity_main);

        // UIs
        Button zoomFF = binding.zoomFF;
        Button zoomFP = binding.zoomFP;
        // !
        Button capBtn = binding.cap;
        Button switchLine = binding.switchLine;
        Button satBtn = binding.satCheck;
        tv1 = binding.tv1;
        tv2 = binding.tv2;
        captureStatusIcon = binding.captureStatusIcon;
        nameText = binding.nameText;
        indicator = binding.indicator;
        qtyText = binding.qtyText;
        TextView focusTxt = binding.focusTxt;
        SeekBar focusBar = binding.focusBar;
        // todo 機能追加
        AppCompatImageView focusLock = binding.focusLock;

        SeekBar lineBar = binding.lineBar;
        FrameLayout line = binding.line;
        TextView isoTxt = binding.isoTxt;
        SeekBar isoBar = binding.isoBar;
        AppCompatImageView isoLock = binding.isoLock;
        AppCompatImageView lineLock = binding.lineLock;
        focusTxt.setText(getString(R.string.focus_format, String.valueOf(focusBar.getProgress())));
        isoTxt.setText(getString(R.string.iso_format, isoBar.getProgress()));
        Log.v("a", "executed onCreate            a");

        switchLine.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                isLine = !isLine;
if (isLine) {
                    line.setAlpha(1f);
                } else {
                    line.setAlpha(0);
                }

            }
        });
        capBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if(nameText.getText().toString().isEmpty()){
                    String message = getString(R.string.error_enter_sequence_name);
                    Cam.setStatus(Cam.StatusType.ERROR, message, captureStatusIcon, indicator);
                    // Cam.java: setStatusメソッドを使用して、エラーメッセージを表示する
                    return;
                }
                int qty;
                try {
                    qty = Integer.parseInt(qtyText.getText().toString().trim());
                } catch (NumberFormatException e) {
                    qty = 0;
                }
                if (qty < 1) {
                    Cam.setStatus(Cam.StatusType.ERROR, "Please enter a capture quantity of 1 or more.", captureStatusIcon, indicator);
                    return;
                }
                // Log.d("a", String.format("start capture %d ms, %d, %f, %d枚,name:%s", (int) (expo / 1000000L), iso, fd,
                //         qty, nameText.getText().toString()));
                // captureボタンの透明度を下げる
                capBtn.setAlpha(0.5f);
                capBtn.setEnabled(false);
                satBtn.setAlpha(0.5f);
                satBtn.setEnabled(false);
                if (!cam.startCaptureSession(expo, iso, fd, qty, nameText.getText().toString(), indicator)) {
                    Cam.setStatus(Cam.StatusType.ERROR, "Camera is not ready. Please try again.", captureStatusIcon, indicator);
                    capBtn.setAlpha(1.0f);
                    capBtn.setEnabled(true);
                    satBtn.setAlpha(1.0f);
                    satBtn.setEnabled(true);
                    return;
                }
                Log.d("BUTTON", "start capture session！");

            }
        });
        // 今の設定で 1 枚だけ試し撮りして, 一次光の領域が白飛びしていないかを確かめる
        satBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (cam.startSaturationCheck(expo, iso, fd, indicator)) {
                    Cam.setStatus(Cam.StatusType.LOADING, getString(R.string.status_checking_saturation),
                            captureStatusIcon, indicator);
                    capBtn.setAlpha(0.5f);
                    capBtn.setEnabled(false);
                    satBtn.setAlpha(0.5f);
                    satBtn.setEnabled(false);
                }
            }
        });
        zoomFF.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // 押すたびに focus と noZoom を行き来する. pointing 中に押したら focus に切り替える
                setZoom(zoom == 1 ? 0 : 1);
            }
        });
        zoomFP.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setZoom(zoom == 2 ? 0 : 2);
            }
        });
        focusBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                fd = (float) i / 100.0f;
                focusTxt.setText(getString(R.string.focus_format, String.valueOf(fd)));

                cam.changeValueOfPreview(0, fd, 0, -1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        focusLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isFocusLocked = !isFocusLocked;

                if (isFocusLocked) {
                    focusBar.setEnabled(false);
                    focusLock.setImageResource(R.drawable.ic_lock);
                    focusLock.setAlpha(0.5f);
                    Toast.makeText(CapActivity.this, R.string.toast_focus_locked, Toast.LENGTH_SHORT).show();
                } else {
                    focusBar.setEnabled(true);
                    focusLock.setImageResource(R.drawable.ic_unlock);
                    focusLock.setAlpha(1.0f);
                    Toast.makeText(CapActivity.this, R.string.toast_focus_unlocked, Toast.LENGTH_SHORT).show();
                }
            }
        });

        isoLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isIsoLocked = !isIsoLocked;
                if (isIsoLocked) {
                    isoBar.setEnabled(false);
                    isoLock.setImageResource(R.drawable.ic_lock);
                    isoLock.setAlpha(0.5f);
                } else {
                    isoBar.setEnabled(true);
                    isoLock.setImageResource(R.drawable.ic_unlock);
                    isoLock.setAlpha(1.0f);
                }
            }
        });

        lineLock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isLineLocked = !isLineLocked;
                if (isLineLocked) {
                    lineBar.setEnabled(false);
                    lineLock.setImageResource(R.drawable.ic_lock);
                    lineLock.setAlpha(0.5f);
                } else {
                    lineBar.setEnabled(true);
                    lineLock.setImageResource(R.drawable.ic_unlock);
                    lineLock.setAlpha(1.0f);
                }
            }
        });

        isoBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                switch (i) {
                    case 1:
                        iso = 50;
                        break;
                    case 2:
                        iso = 64;
                        break;
                    case 3:
                        iso = 80;
                        break;
                    case 4:
                        iso = 100;
                        break;
                    case 5:
                        iso = 125;
                        break;
                    case 6:
                        iso = 160;
                        break;
                    case 7:
                        iso = 200;
                        break;
                    case 8:
                        iso = 250;
                        break;
                    case 9:
                        iso = 320;
                        break;
                    case 10:
                        iso = 400;
                        break;
                    case 11:
                        iso = 500;
                        break;
                    case 12:
                        iso = 640;
                        break;
                    case 13:
                        iso = 800;
                        break;
                    case 14:
                        iso = 1600;
                        break;
                    case 15:
                        iso = 3200;
                        break;
                }

                // カメラが受け付けない ISO は受け付ける範囲に収める (表示も実際の値にする)
                iso = cam.clampIso(iso);
                isoTxt.setText(getString(R.string.iso_format, iso));
                cam.changeValueOfPreview(iso, -100, 0, -1);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // 自動露出がオンなら, 新しい ISO で測り直す
                exposureControls.onIsoChanged();
            }
        });
        lineBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                updateLineY();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // capture sounds
        AudioAttributes audioAttr = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();
        soundPool = new SoundPool.Builder().setAudioAttributes(audioAttr).setMaxStreams(2).build();
        alarmSound = soundPool.load(this, R.raw.technoalarm, 1);
        shatterSound = soundPool.load(this, R.raw.shatter, 1);

        // cam object (使うカメラは端末プロファイルで決める. 書かれていなければ RAW で撮れる背面カメラ)
        camId = DeviceProfiles.cameraId(this);
        cam = new Cam(this, camId, soundPool, alarmSound, shatterSound, tv1, tv2, captureStatusIcon);

        // 露光時間 (ダイヤル / 目盛り, ×2 ÷2, 数値入力, 自動露出, ロック)
        exposureControls = new ExposureControls(this, binding, new ExposureControls.Host() {
            @Override
            public void onExposureChanged(long ns) {
                expo = ns;
                cam.changeValueOfPreview(0, -100, expo, -1);
            }

            @Override
            public boolean startAutoExposure(long startNs, long minNs, long maxNs,
                    Cam.AutoExposureListener listener) {
                if (!cam.startAutoExposure(startNs, minNs, maxNs, iso, fd, listener)) {
                    return false;
                }
                // 測り終わったら Cam がボタンを戻す
                capBtn.setAlpha(0.5f);
                capBtn.setEnabled(false);
                satBtn.setAlpha(0.5f);
                satBtn.setEnabled(false);
                return true;
            }

            @Override
            public void cancelAutoExposure() {
                cam.cancelAutoExposure();
            }

            @Override
            public void showStatus(Cam.StatusType type, String message) {
                Cam.setStatus(type, message, captureStatusIcon, indicator);
            }
        });

        // request camera permission (カメラを開くのは onResume で行う)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA },
                    CAMERA_PERMISSION_REQUEST_CODE);
        }

    }

    // ズームを切り替える (0:noZoom 1:focusing 2:pointing).
    // 2 つのボタンの表示, プレビュー, ガイド線の位置をまとめてそろえる
    private void setZoom(int mode) {
        zoom = mode;
        binding.zoomFF.setText(mode == 1 ? R.string.unzoom : R.string.zoom_for_focusing);
        binding.zoomFP.setText(mode == 2 ? R.string.unzoom : R.string.zoom_for_pointing);
        cam.setZoom(mode);
        updateLineY();
    }

    // ガイド線の位置 (lineBar: 0 ~ 1000). pointing ではプレビューが拡大されるので別の位置.
    // 筐体と画面によるので端末プロファイルから
    private void updateLineY() {
        DeviceProfile profile = DeviceProfiles.current(this);
        int i = binding.lineBar.getProgress();
        if (zoom == 2) {
            binding.line.setY((float) profile.guideLineYPointing() + i / 10);
        } else {
            binding.line.setY((float) profile.guideLineY() + i / 10);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        cam.startBackgroundThread();
        cam.setupCam();
        // onPause で撮影が中断されていたらボタンを押せる状態に戻す
        if (!cam.isCapturing()) {
            binding.cap.setAlpha(1.0f);
            binding.cap.setEnabled(true);
            binding.satCheck.setAlpha(1.0f);
            binding.satCheck.setEnabled(true);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        cam.closeCam();
        cam.stopBackgroundThread();
    }

    /*
     * @Override
     * public void surfaceChanged(@NonNull SurfaceHolder surfaceHolder, int i, int
     * i1, int i2) {
     * 
     * }
     * 
     * //@Override
     * public void surfaceCreated(@NonNull SurfaceHolder holder){
     * if(ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
     * PackageManager.PERMISSION_GRANTED){
     * openCam(camId, holder.getSurface());
     * //showStr(s);
     * }
     * }
     * 
     * // when surface closed
     * //@Override
     * public void surfaceDestroyed(@NonNull SurfaceHolder holder){
     * //closeCam();
     * }
     */

}
