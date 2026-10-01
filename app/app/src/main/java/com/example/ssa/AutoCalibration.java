// SPDX-License-Identifier: MIT
// Copyright © 2026 Tsuyoshi Kobayashi(legrs4073)
package com.example.ssa;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 自動校正で使う画像を MediaStore から探して解析する (Android 依存部分).
 * 判定ロジックは {@link SpectrumCalibrator} にある.
 */
final class AutoCalibration {

    private AutoCalibration() {
    }

    /** 画像の解析は重いので UI スレッドの外で, 1つずつ実行する */
    static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /** 解析に使う画像. ダーク減算済みを優先し, 読めなければ stacked.tif を使う */
    static final String[] IMAGE_NAMES = {"darked.tif", "stacked.tif"};

    /** 解析した画像とそのファイル名 */
    static final class Analysis {
        final String fileName;
        final SpectrumCalibrator.ImageProfile image;

        Analysis(String fileName, SpectrumCalibrator.ImageProfile image) {
            this.fileName = fileName;
            this.image = image;
        }
    }

    static String imageDir(String seq) {
        return "Documents/FUKASIS-app/imgs/" + seq + "/";
    }

    static Uri find(ContentResolver resolver, String path, String name) {
        Uri collection = MediaStore.Files.getContentUri("external");
        String sel = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
        try (Cursor c = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID}, sel,
                new String[]{name, path}, null)) {
            if (c != null && c.moveToFirst()) {
                long id = c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return ContentUris.withAppendedId(collection, id);
            }
        }
        return null;
    }

    /**
     * seq の darked.tif → stacked.tif の順に, 解析できた最初の画像を返す.
     * 空や壊れたファイル (失敗したダーク減算の残骸など) は飛ばして次の候補を使う.
     * 重いので UI スレッドから呼ばないこと.
     */
    static Analysis analyzeSequence(ContentResolver resolver, String seq) throws SpectrumCalibrator.CalibrationException {
        boolean found = false;
        for (String name : IMAGE_NAMES) {
            Uri uri = find(resolver, imageDir(seq), name);
            if (uri == null) {
                continue;
            }
            found = true;
            try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(uri, "r")) {
                if (pfd == null) {
                    continue;
                }
                SpectrumCalibrator.ImageProfile img =
                        SpectrumCalibrator.ImageProfile.fromNative(SpectrumCalibrator.analyzeImageNative(pfd.getFd()));
                if (img != null) {
                    return new Analysis(name, img);
                }
                Log.d("AutoCalib", name + " を解析できませんでした");
            } catch (IOException | RuntimeException e) {
                Log.e("AutoCalib", "failed to open " + name, e);
            }
        }
        throw new SpectrumCalibrator.CalibrationException(found
                ? seq + " の darked.tif / stacked.tif を読み込めません"
                : seq + " に darked.tif / stacked.tif が見つかりません");
    }
}
