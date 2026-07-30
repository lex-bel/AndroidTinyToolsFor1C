package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.graphics.Bitmap;
import android.util.Base64;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.io.ByteArrayOutputStream;

public class QrCodeGeneratorClass {
    public QrCodeGeneratorClass(Activity context) {
    }

    public String generateQrCodeBase64(String text, int size) {
        if (text == null || size <= 0) {
            return "";
        }

        try {
            BitMatrix bitMatrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);

            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, bitMatrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF);
                }
            }

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream);
            byte[] pngBytes = outputStream.toByteArray();
            outputStream.close();

            return Base64.encodeToString(pngBytes, Base64.NO_WRAP);
        } catch (Exception exception) {
            return "";
        }
    }
}
