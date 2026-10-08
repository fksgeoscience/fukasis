// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;
import java.io.OutputStream;
import android.app.Activity;
import android.content.ContentValues;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.content.ContentUris;

import androidx.appcompat.app.AppCompatActivity;

import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;

import com.example.ssa.databinding.ActivityCalibBinding;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import android.content.ContentResolver;
import android.provider.MediaStore;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.util.Locale;
import android.widget.Toast;

public class CalibActivity extends AppCompatActivity{

    //435.8, 546.1, 588.0, 611.6
    private ImageView iv1;
    private ImageView iv2;
    private EditText path_et1; //et=EditText
    private EditText path_et2; //et=EditText

    private ActivityCalibBinding binding;
    private Activity activity = this;

    int[] pos = {0,0};
    float scale = 0.8F;
    int iv1_ofs = -1200;
    int iv2_ofs = 350;
    int imgWidth ;
    int imgHeight ;
    int dispWidth1 ;
    int dispWidth2 ;
    int dispHeight;
    int fol;
    int[] t = {0,0,0,0};
    float[] c = {0,0,0,0};
    SeekBar[] sb;
    TextView brightnessTxt;
    TextView[] tv;
    EditText[] et;
    FrameLayout[] line;
    // 追加の校正点. チェックを入れたものだけ校正に使う
    int[] tExtra = {0,0};
    CheckBox[] checkExtra;
    SeekBar[] sbExtra;
    TextView[] tvExtra;
    EditText[] etExtra;
    FrameLayout[] lineExtra;

    private void changesb(int j, int i){
        tv[j].setText("" + i);
        t[j] = (imgWidth - i);
        Log.d("a", Integer.toString(fol - t[j]));
        line[j].setX((t[j] +iv1_ofs)*scale);
        line[j].setY(pos[1]-50);
    }

    private void changeExtra(int j, int i){
        tvExtra[j].setText("" + i);
        tExtra[j] = (imgWidth - i);
        lineExtra[j].setX((tExtra[j] +iv1_ofs)*scale);
        lineExtra[j].setY(pos[1]-50);
    }

    private void setExtraEnabled(int j, boolean enabled){
        sbExtra[j].setEnabled(enabled);
        etExtra[j].setEnabled(enabled);
        lineExtra[j].setVisibility(enabled ? View.VISIBLE : View.INVISIBLE);
        if(enabled){
            changeExtra(j, sbExtra[j].getProgress());
        }
    }

    // プレビュー画像の表示上の明るさを変える (i=10 ごとに2倍)。書き出す校正データには影響しない
    private void changeBrightness(int i){
        float gain = (float)Math.pow(2.0, i / 10.0);
        ColorMatrix cm = new ColorMatrix();
        cm.setScale(gain, gain, gain, 1.0F);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(cm);
        iv1.setColorFilter(filter);
        iv2.setColorFilter(filter);
        brightnessTxt.setText(getString(R.string.brightness_format, gain));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityCalibBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        //setContentView(R.layout.activity_main);

        // UIs
        Button openBtn = binding.open;
        Button exportBtn = binding.export;
        SeekBar sb1 = binding.sb1;
        TextView t1 = binding.t1;
        sb = new SeekBar[]{binding.sb2,binding.sb3,binding.sb4,binding.sb5};
        tv = new TextView[]{binding.t2,binding.t3,binding.t4,binding.t5};
        et = new EditText[]{binding.c1,binding.c2,binding.c3,binding.c4};
        //SeekBar sb3 = binding.sb3;
        //TextView t3 = binding.t3;
        //SeekBar sb4 = binding.sb4;
        //TextView t4 = binding.t4;
        //SeekBar sb5 = binding.sb5;
        //TextView t5 = binding.t5;
        FrameLayout l1 = binding.l1;
        line = new FrameLayout[]{binding.l2,binding.l3,binding.l4,binding.l5};
        checkExtra = new CheckBox[]{binding.extraCheck1,binding.extraCheck2};
        sbExtra = new SeekBar[]{binding.sb6,binding.sb7};
        tvExtra = new TextView[]{binding.t6,binding.t7};
        etExtra = new EditText[]{binding.c5,binding.c6};
        lineExtra = new FrameLayout[]{binding.l6,binding.l7};
        for(int k=0; k<checkExtra.length; k++){
            final int j = k;
            setExtraEnabled(j, checkExtra[j].isChecked());
            checkExtra[j].setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton btn, boolean b) {
                    setExtraEnabled(j, b);
                }
            });
            sbExtra[j].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                    if(checkExtra[j].isChecked()){
                        changeExtra(j, i);
                    }
                }
                @Override
                public void onStartTrackingTouch(SeekBar seekBar) {
                }
                @Override
                public void onStopTrackingTouch(SeekBar seekBar) {
                }
            });
        }
        iv1 = binding.iv1;
        iv1.setScaleType(ImageView.ScaleType.MATRIX);
        iv2 = binding.iv2;
        iv2.setScaleType(ImageView.ScaleType.MATRIX);
        SeekBar brightnessBar = binding.brightnessBar;
        brightnessTxt = binding.brightnessTxt;
        changeBrightness(brightnessBar.getProgress());

        
        path_et1 = binding.input1;
        path_et2 = binding.input2;
        openBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = getContentResolver();
                Uri collection = MediaStore.Files.getContentUri("external");
                Uri uri = null;

                String filepath = "Documents/FUKASIS-app/imgs/" + path_et1.getText().toString() + "/";
                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                
                //  jpg image ( for preview )

                //* ファイル名を指定
                String filename = "stacked.jpg";
                String[] selectionArgs = new String[]{filename, filepath};

                try(Cursor cursor = resolver.query(
                            collection,
                            new String[]{MediaStore.MediaColumns._ID},
                            selection,
                            selectionArgs,
                            null)){
                    //* */ filenameをlog
                    Log.d("a", "filename: " + filename);
                    
                    if(cursor != null && cursor.moveToFirst()){
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                        // exists
                        uri = ContentUris.withAppendedId(collection, id);
                        Log.d("a","ありましたよっ！");
                    }else{

                        Log.d("a","な、ないです…");
                        Log.d("a","uri = " + uri);
                        Log.d("a","filepath = " + filepath);
                    }

                }
                if(uri != null){
                    iv1.setImageURI(uri);
                    iv2.setImageURI(uri);
                    Log.d("a", "open");
                    Matrix matrix = new Matrix();
                    dispWidth1 = iv1.getWidth();
                    dispWidth2 = iv2.getWidth();
                    dispHeight = iv1.getHeight();
                    imgWidth = iv1.getDrawable().getIntrinsicWidth();
                    imgHeight = iv1.getDrawable().getIntrinsicHeight();
                    Log.d("a","" + dispWidth1);
                    Log.d("a","" + dispWidth2);
                    Log.d("a","" + dispHeight);
                    Log.d("a","" + imgWidth);
                    Log.d("a","" + imgHeight);
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(scale*iv1_ofs, -(scale*imgHeight-dispHeight)/2);
                    iv1.setImageMatrix(matrix);

                    matrix = new Matrix();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(dispWidth2 - scale*(imgWidth-iv2_ofs), -(scale*imgHeight-dispHeight)/2);
                    iv2.setImageMatrix(matrix);

                    iv2.getLocationOnScreen(pos);
                }

            }
        });
                FloatingActionButton homeButton = binding.homeButton;
        homeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish(); // Finish the current activity and return to the previous one
            }
        });
        exportBtn.setOnClickListener(new View.OnClickListener(){
            public void onClick(View v){
                ContentResolver resolver = activity.getContentResolver();

                ContentValues valuesCsv = new ContentValues();
                Uri uriCsv = Cam.getUri(activity,"Documents/FUKASIS-app/csv/calibdata/", path_et2.getText().toString() + ".csv", "text/csv",resolver , valuesCsv);

                if(uriCsv != null){
                    try(OutputStream output = activity.getContentResolver().openOutputStream(uriCsv)){
                        for(int i=0; i<4; i++){
                            c[i] = Float.parseFloat(et[i].getText().toString());
                        }
                        // 校正点を集める. 基本の 4 本と, チェックの入っている追加分
                        // (t[] 自体を書き換えると, 2回目以降の出力や線の位置がずれてしまう)
                        int extraCount = 0;
                        for(int j=0; j<checkExtra.length; j++){
                            if(checkExtra[j].isChecked()){
                                extraCount++;
                            }
                        }
                        double[] tRel = new double[4 + extraCount];
                        double[] cRef = new double[4 + extraCount];
                        for(int i=0; i<4; i++){
                            tRel[i] = fol - t[i];
                            //folとの相対
                            cRef[i] = c[i];
                        }
                        int n = 4;
                        for(int j=0; j<checkExtra.length; j++){
                            if(checkExtra[j].isChecked()){
                                tRel[n] = fol - tExtra[j];
                                cRef[n] = Float.parseFloat(etExtra[j].getText().toString());
                                n++;
                            }
                        }
                        // 1 行目が位置, 2 行目が波長. 列の数が校正点の数
                        // 小数点がカンマになる言語設定でも csv が壊れないように Locale を固定
                        StringBuilder line1 = new StringBuilder();
                        StringBuilder line2 = new StringBuilder();
                        for(int i=0; i<n; i++){
                            if(i > 0){
                                line1.append(",");
                                line2.append(",");
                            }
                            line1.append(String.format(Locale.US, "%d", (int)tRel[i]));
                            line2.append(String.format(Locale.US, "%f", cRef[i]));
                        }
                        String dat = line1 + "\n" + line2;

                        output.write(dat.getBytes("UTF-8"));

                        valuesCsv.clear();
                        valuesCsv.put(MediaStore.MediaColumns.IS_PENDING, 0);
                        resolver.update(uriCsv, valuesCsv, null, null);

                        Log.d("a", "csv saved at "+uriCsv.toString());

                        // この校正データで csv 画面が出力する波長の範囲を確かめて知らせる
                        CalibrationValidator.Result check = CalibrationValidator.validate(tRel, cRef, fol);
                        int wlMin = (int)Math.round(check.wavelengthMin);
                        int wlMax = (int)Math.round(check.wavelengthMax);
                        String message;
                        switch(check.status){
                            case CalibrationValidator.TRUNCATED:
                                message = getString(R.string.calib_warning_truncated, wlMin, wlMax);
                                break;
                            case CalibrationValidator.NO_OUTPUT:
                                message = getString(R.string.calib_warning_no_output);
                                break;
                            default:
                                message = getString(R.string.calib_saved, wlMin, wlMax);
                                break;
                        }
                        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                    }catch(IOException e){
                        e.printStackTrace();
                        resolver.delete(uriCsv, null, null);
                    }
                }

            }
        });
        brightnessBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changeBrightness(i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        // スクロールしても線が画像についてくるようにする
        binding.scroll.setOnScrollChangeListener(new View.OnScrollChangeListener() {
            @Override
            public void onScrollChange(View v, int x, int y, int oldX, int oldY) {
                if(imgWidth == 0){
                    return;
                }
                iv2.getLocationOnScreen(pos);
                l1.setY(pos[1]-50);
                for(int j=0; j<4; j++){
                    line[j].setY(pos[1]-50);
                }
                for(int j=0; j<lineExtra.length; j++){
                    lineExtra[j].setY(pos[1]-50);
                }
            }
        });
        sb1.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                Log.d("a","" + i);
                t1.setText("" + i);
                fol = imgWidth - i;
                l1.setX(pos[0]+dispWidth2+(-imgWidth + fol + iv2_ofs)*scale);
                l1.setY(pos[1]-50);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[0].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(0,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[1].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(1,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[2].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(2,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        sb[3].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int i, boolean b) {
                changesb(3,i);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });


    }
    @Override
    protected void onResume(){
        super.onResume();
        
    }
    @Override
    protected void onPause(){
        super.onPause();
    }


}
