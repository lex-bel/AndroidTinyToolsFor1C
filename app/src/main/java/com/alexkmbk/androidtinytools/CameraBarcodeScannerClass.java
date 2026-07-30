package com.alexkmbk.androidtinytools;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.DecodeHintType;
import com.google.zxing.ResultPoint;
import com.journeyapps.barcodescanner.BarcodeCallback;
import com.journeyapps.barcodescanner.BarcodeResult;
import com.journeyapps.barcodescanner.BarcodeView;
import com.journeyapps.barcodescanner.DefaultDecoderFactory;
import com.journeyapps.barcodescanner.Size;
import com.journeyapps.barcodescanner.camera.CameraSettings;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

public class CameraBarcodeScannerClass implements Runnable {
    public static final int SCAN_MODE_DEFAULT = 0;
    public static final int SCAN_MODE_FAST_1D = 1;
    public static final int SCAN_MODE_QR = 2;
    public static final int SCAN_MODE_DATA_MATRIX = 3;
    public static final int SCAN_MODE_QR_DATA_MATRIX = 4;

    private static final String PREFS_NAME = "CameraScanner";
    private static final String PREF_FRAME_W = "frame_w_frac";
    private static final String PREF_FRAME_H = "frame_h_frac";
    private static final String PREF_ZOOM = "zoom_progress"; // SeekBar progress 0-100

    private final Activity mContext;
    private final long mV8Object;
    private boolean enableTorch;
    private int scanMode;
    private boolean torchEnabled;
    private String hintText;

    private static Dialog scanDialog;
    private static BarcodeView barcodeView;

    // Список коэффициентов зума камеры (x100, напр. 100=1.0x, 200=2.0x).
    // Заполняется после открытия камеры; хранится статически для переиспользования.
    @SuppressWarnings("deprecation")
    private static List<Integer> cameraZoomRatios = null;

    static native void OnBarcode(long pObject, String sBarcode);

    public CameraBarcodeScannerClass(Activity mContext, long v8Object) {
        this.mContext = mContext;
        this.mV8Object = v8Object;
    }

    public void startScan(boolean enableTorch, int scanMode, String hintText) {
        this.enableTorch = enableTorch;
        this.scanMode = scanMode;
        this.hintText = hintText;
        mContext.runOnUiThread(this);
    }

    public void stopScan() {
        mContext.runOnUiThread(this::stopScanner);
    }

    @Override
    public void run() {
        if (!Utils.checkCameraPermission(mContext)) {
            return;
        }

        try {
            System.loadLibrary("AndroidTinyTools_" + Constants.version);
        } catch (UnsatisfiedLinkError e) {
            new ToastClass(mContext, e.getMessage()).toast();
            return;
        }

        if (scanDialog != null && scanDialog.isShowing()) {
            return;
        }

        Collection<BarcodeFormat> formats = getFormatsForMode(scanMode);
        Size initialFramingSize = loadFramingSize();

        barcodeView = new BarcodeView(mContext);
        barcodeView.setBackgroundColor(Color.BLACK);
        barcodeView.setDecoderFactory(new DefaultDecoderFactory(formats, getDecoderHints(), null, 0));
        barcodeView.setFramingRectSize(initialFramingSize);

        CameraSettings cameraSettings = barcodeView.getCameraSettings();
        cameraSettings.setAutoFocusEnabled(true);
        cameraSettings.setContinuousFocusEnabled(true);
        cameraSettings.setMeteringEnabled(true);
        cameraSettings.setExposureEnabled(enableTorch);
        barcodeView.setCameraSettings(cameraSettings);

        torchEnabled = enableTorch;

        FrameLayout rootLayout = new FrameLayout(mContext);
        rootLayout.setBackgroundColor(Color.BLACK);
        rootLayout.addView(barcodeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Интерактивный оверлей — пользователь тянет за угол или край области сканирования.
        // Размер сохраняется в SharedPreferences для следующего вызова.
        ScanOverlayView overlayView = new ScanOverlayView(mContext, initialFramingSize,
                (wFrac, hFrac) -> saveFramingSize(wFrac, hFrac));
        rootLayout.addView(overlayView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        Button torchButton = new Button(mContext);
        torchButton.setAllCaps(false);
        torchButton.setText(torchEnabled ? "Фонарик: вкл" : "Фонарик: выкл");
        torchButton.setOnClickListener(v -> {
            torchEnabled = !torchEnabled;
            try {
                if (barcodeView != null) {
                    barcodeView.setTorch(torchEnabled);
                }
            } catch (Throwable ignored) {
            }
            torchButton.setText(torchEnabled ? "Фонарик: вкл" : "Фонарик: выкл");
        });
        FrameLayout.LayoutParams torchButtonParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        torchButtonParams.gravity = Gravity.TOP | Gravity.END;
        int margin = dp(16);
        torchButtonParams.setMargins(margin, margin, margin, margin);
        rootLayout.addView(torchButton, torchButtonParams);

        // Нижняя панель: полоса зума + подсказка
        final SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        final int savedZoomProgress = prefs.getInt(PREF_ZOOM, 0);

        LinearLayout bottomPanel = new LinearLayout(mContext);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setBackgroundColor(0xCC000000);
        // Перехватываем касания, чтобы оверлей не реагировал в зоне нижней панели
        bottomPanel.setClickable(true);

        // --- Строка зума ---
        LinearLayout zoomRow = new LinearLayout(mContext);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER_VERTICAL);
        int zoomPad = dp(8);
        zoomRow.setPadding(dp(12), zoomPad, dp(12), zoomPad);

        TextView zoomIcon = new TextView(mContext);
        zoomIcon.setText("Зум");
        zoomIcon.setTextColor(0xAAFFFFFF);
        zoomIcon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        zoomRow.addView(zoomIcon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        SeekBar zoomBar = new SeekBar(mContext);
        zoomBar.setMax(100);
        zoomBar.setProgress(savedZoomProgress);
        LinearLayout.LayoutParams zoomBarParams =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        zoomBarParams.setMargins(dp(8), 0, dp(8), 0);
        zoomRow.addView(zoomBar, zoomBarParams);

        TextView zoomLabel = new TextView(mContext);
        zoomLabel.setTextColor(Color.WHITE);
        zoomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        zoomLabel.setMinWidth(dp(38));
        zoomLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        zoomLabel.setText(zoomProgressToLabel(savedZoomProgress));
        zoomRow.addView(zoomLabel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bottomPanel.addView(zoomRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // --- Подсказка под полосой зума ---
        if (hintText != null && !hintText.isEmpty()) {
            View divider = new View(mContext);
            divider.setBackgroundColor(0x33FFFFFF);
            bottomPanel.addView(divider, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));

            TextView hintView = new TextView(mContext);
            hintView.setText(hintText);
            hintView.setTextColor(Color.WHITE);
            hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            hintView.setGravity(Gravity.CENTER);
            int hintPad = dp(10);
            hintView.setPadding(hintPad, hintPad, hintPad, hintPad);
            bottomPanel.addView(hintView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams bottomPanelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomPanelParams.gravity = Gravity.BOTTOM;
        rootLayout.addView(bottomPanel, bottomPanelParams);

        // Обработка изменения ползунка зума
        final int[] currentProgress = {savedZoomProgress};
        zoomBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentProgress[0] = progress;
                zoomLabel.setText(zoomProgressToLabel(progress));
                applyLegacyZoom(progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Сохраняем позицию ползунка при отпускании
                prefs.edit().putInt(PREF_ZOOM, currentProgress[0]).apply();
            }
        });

        scanDialog = new Dialog(mContext, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        scanDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        scanDialog.setContentView(rootLayout, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        scanDialog.setCancelable(true);
        scanDialog.setOnDismissListener(dialog -> stopScanner());

        Window window = scanDialog.getWindow();
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        // Подтверждение N=2: один и тот же код должен совпасть дважды подряд,
        // прежде чем результат будет принят. Устраняет большинство phantom reads.
        final int CONFIRM_THRESHOLD = 2;
        final String[] lastSeen = {null};
        final int[] confirmCount = {0};
        final boolean[] handled = {false};

        barcodeView.decodeContinuous(new BarcodeCallback() {
            @Override
            public void barcodeResult(BarcodeResult result) {
                if (result == null || handled[0]) {
                    return;
                }
                String value = result.getText();
                if (value == null || value.isEmpty()) {
                    return;
                }

                if (value.equals(lastSeen[0])) {
                    confirmCount[0]++;
                    if (confirmCount[0] >= CONFIRM_THRESHOLD) {
                        handled[0] = true;
                        OnBarcode(mV8Object, value);
                        if (scanDialog != null && scanDialog.isShowing()) {
                            scanDialog.dismiss();
                        }
                    }
                } else {
                    // Новый код — сбрасываем счётчик
                    lastSeen[0] = value;
                    confirmCount[0] = 1;
                }
            }

            @Override
            public void possibleResultPoints(List<ResultPoint> resultPoints) {
            }
        });

        scanDialog.show();
        barcodeView.resume();

        try {
            barcodeView.setTorch(torchEnabled);
        } catch (Throwable ignored) {
        }

        // Читаем список коэффициентов зума после открытия камеры и применяем сохранённый зум.
        // Задержка нужна, чтобы камера успела инициализироваться.
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            fetchCameraZoomRatios();
            // Обновляем метку с реальным значением зума
            zoomLabel.setText(zoomProgressToLabel(currentProgress[0]));
            if (currentProgress[0] > 0) {
                applyLegacyZoom(currentProgress[0]);
            }
        }, 900);
    }

    private void stopScanner() {
        if (barcodeView != null) {
            try {
                barcodeView.setTorch(false);
            } catch (Throwable ignored) {
            }
            barcodeView.pause();
            barcodeView = null;
        }
        scanDialog = null;
    }

    // ---------------------------------------------------------------------------
    // Зум через legacy Camera API (android.hardware.Camera)
    // ---------------------------------------------------------------------------

    // Читает доступные коэффициенты зума из открытой камеры.
    // Коэффициенты задаются в единицах ×100 (напр. 100 = 1.0x, 200 = 2.0x).
    @SuppressWarnings("deprecation")
    private static void fetchCameraZoomRatios() {
        android.hardware.Camera camera = getLegacyCamera();
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            if (params.isZoomSupported()) {
                List<Integer> ratios = params.getZoomRatios();
                if (ratios != null && !ratios.isEmpty()) {
                    cameraZoomRatios = ratios;
                }
            }
        } catch (Exception ignored) {
        }
    }

    // Применяет зум к открытой камере. progress — позиция ползунка (0–100).
    @SuppressWarnings("deprecation")
    private static void applyLegacyZoom(int progress) {
        android.hardware.Camera camera = getLegacyCamera();
        if (camera == null) return;
        try {
            android.hardware.Camera.Parameters params = camera.getParameters();
            if (!params.isZoomSupported()) return;
            int maxZoom = params.getMaxZoom();
            int targetZoom = progress * maxZoom / 100;
            params.setZoom(Math.min(targetZoom, maxZoom));
            camera.setParameters(params);
        } catch (Exception ignored) {
        }
    }

    // Возвращает текстовую метку зума для текущей позиции ползунка.
    // Использует реальные коэффициенты камеры если они доступны, иначе приближение.
    private static String zoomProgressToLabel(int progress) {
        if (cameraZoomRatios != null && !cameraZoomRatios.isEmpty()) {
            int index = progress * (cameraZoomRatios.size() - 1) / 100;
            index = Math.max(0, Math.min(index, cameraZoomRatios.size() - 1));
            float ratio = cameraZoomRatios.get(index) / 100.0f;
            return String.format("%.1fx", ratio);
        }
        // Пока камера не открыта — показываем приближённое значение
        return progress == 0 ? "1.0x" : String.format("%.1fx", 1.0f + progress * 0.07f);
    }

    // Извлекает экземпляр android.hardware.Camera из внутренних полей BarcodeView через рефлексию.
    // BarcodeView (CameraPreview) → cameraInstance (CameraInstance) → cameraManager → camera
    @SuppressWarnings("deprecation")
    private static android.hardware.Camera getLegacyCamera() {
        if (barcodeView == null) return null;
        try {
            Object cameraInstance = getFieldValue(barcodeView, "cameraInstance");
            if (cameraInstance == null) return null;
            Object cameraManager = getFieldValue(cameraInstance, "cameraManager");
            if (cameraManager == null) return null;
            // Пробуем несколько возможных имён поля
            for (String name : new String[]{"camera", "theCamera", "mCamera"}) {
                Object cam = getFieldValue(cameraManager, name);
                if (cam instanceof android.hardware.Camera) {
                    return (android.hardware.Camera) cam;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // Вспомогательный метод: ищет поле по имени в иерархии классов объекта
    private static Object getFieldValue(Object obj, String fieldName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (Exception ignored) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------------
    // Сохранение/восстановление области сканирования
    // ---------------------------------------------------------------------------

    // Загружает сохранённый размер области из SharedPreferences.
    // При первом запуске возвращает пресет по режиму сканирования.
    private Size loadFramingSize() {
        SharedPreferences prefs = mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int screenW = mContext.getResources().getDisplayMetrics().widthPixels;
        int screenH = mContext.getResources().getDisplayMetrics().heightPixels;

        if (prefs.contains(PREF_FRAME_W) && prefs.contains(PREF_FRAME_H)) {
            int w = Math.max(dp(80), (int) (screenW * prefs.getFloat(PREF_FRAME_W, 0.65f)));
            int h = Math.max(dp(60), (int) (screenH * prefs.getFloat(PREF_FRAME_H, 0.40f)));
            return new Size(w, h);
        }

        return getDefaultFramingSize(screenW, screenH);
    }

    private void saveFramingSize(float widthFrac, float heightFrac) {
        mContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putFloat(PREF_FRAME_W, widthFrac)
                .putFloat(PREF_FRAME_H, heightFrac)
                .apply();
    }

    // Пресеты размера по умолчанию для первого запуска
    private Size getDefaultFramingSize(int screenW, int screenH) {
        float wFrac, hFrac;
        switch (scanMode) {
            case SCAN_MODE_FAST_1D:  wFrac = 0.78f; hFrac = 0.28f; break;
            case SCAN_MODE_QR:
            case SCAN_MODE_DATA_MATRIX:
            case SCAN_MODE_QR_DATA_MATRIX: wFrac = 0.60f; hFrac = 0.55f; break;
            default: wFrac = 0.68f; hFrac = 0.40f; break;
        }
        return new Size((int) (screenW * wFrac), (int) (screenH * hFrac));
    }

    private Map<DecodeHintType, Object> getDecoderHints() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        // TRY_HARDER заставляет декодер делать больше попыток на кадре (другие углы, масштабы).
        // Включаем для всех режимов — улучшает распознавание искажённых и мелких кодов.
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        // ALSO_INVERTED пробует декодировать инвертированные коды (белые символы на тёмном фоне).
        // Требует zxing core 3.5.1+.
        hints.put(DecodeHintType.ALSO_INVERTED, Boolean.TRUE);
        return hints;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mContext.getResources().getDisplayMetrics());
    }

    private Collection<BarcodeFormat> getFormatsForMode(int scanMode) {
        switch (scanMode) {
            case SCAN_MODE_FAST_1D:
                return Arrays.asList(
                        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
                        BarcodeFormat.CODABAR, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
                        BarcodeFormat.ITF, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E
                );
            case SCAN_MODE_QR:
                return Arrays.asList(BarcodeFormat.QR_CODE);
            case SCAN_MODE_DATA_MATRIX:
                return Arrays.asList(BarcodeFormat.DATA_MATRIX);
            case SCAN_MODE_QR_DATA_MATRIX:
                return Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX);
            default:
                List<BarcodeFormat> formats = new ArrayList<>(Arrays.asList(
                        BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                        BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93,
                        BarcodeFormat.CODABAR, BarcodeFormat.EAN_8, BarcodeFormat.EAN_13,
                        BarcodeFormat.ITF, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E
                ));
                return formats;
        }
    }

    // ---------------------------------------------------------------------------
    // Интерактивный оверлей с изменяемой областью сканирования
    // ---------------------------------------------------------------------------
    private static final class ScanOverlayView extends View {

        interface OnFrameSavedListener {
            void onSaved(float widthFrac, float heightFrac);
        }

        // Тип захваченной ручки при перетаскивании
        private enum Handle { NONE, TL, TR, BL, BR, TOP, BOTTOM, LEFT, RIGHT }

        private final Paint maskPaint = new Paint();
        private final Paint borderPaint = new Paint();
        private final Paint cornerPaint = new Paint();
        private final Paint handlePaint = new Paint();

        private final Size initialSize;
        private final OnFrameSavedListener listener;

        private final Rect framingRect = new Rect();
        private final Rect dragStartRect = new Rect();
        private Handle activeHandle = Handle.NONE;
        private float touchStartX, touchStartY;

        private final int touchTarget;  // радиус зоны касания для ручки
        private final int handleRadius; // радиус нарисованного кружка ручки
        private final int cornerArm;    // длина уголка в углах рамки
        private final int minSize;      // минимальный размер области

        ScanOverlayView(Context context, Size initialSize, OnFrameSavedListener listener) {
            super(context);
            this.initialSize = initialSize;
            this.listener = listener;

            maskPaint.setColor(0x88000000);

            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setColor(Color.WHITE);
            borderPaint.setStrokeWidth(dp(1.5f));

            // Уголки рисуем жирнее, чтобы они хорошо читались
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setColor(Color.WHITE);
            cornerPaint.setStrokeWidth(dp(3));
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);

            handlePaint.setStyle(Paint.Style.FILL);
            handlePaint.setColor(Color.WHITE);

            touchTarget = dp(28);
            handleRadius = dp(5);
            cornerArm = dp(20);
            minSize = dp(80);

            setWillNotDraw(false);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            // Центрируем начальную область при первом layout
            if (oldw == 0 && oldh == 0 && w > 0 && h > 0) {
                int fw = Math.min(initialSize.width, w * 95 / 100);
                int fh = Math.min(initialSize.height, h * 95 / 100);
                framingRect.set((w - fw) / 2, (h - fh) / 2, (w + fw) / 2, (h + fh) / 2);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            float x = event.getX(), y = event.getY();

            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    activeHandle = findHandle(x, y);
                    if (activeHandle != Handle.NONE) {
                        touchStartX = x;
                        touchStartY = y;
                        dragStartRect.set(framingRect);
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (activeHandle == Handle.NONE) return false;
                    applyDrag(x - touchStartX, y - touchStartY);
                    // Обновляем зону декодирования в реальном времени
                    if (barcodeView != null) {
                        barcodeView.setFramingRectSize(new Size(framingRect.width(), framingRect.height()));
                        barcodeView.requestLayout();
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (activeHandle != Handle.NONE) {
                        activeHandle = Handle.NONE;
                        // Сохраняем как долю экрана — работает при любом разрешении
                        if (listener != null && getWidth() > 0 && getHeight() > 0) {
                            listener.onSaved(
                                    (float) framingRect.width() / getWidth(),
                                    (float) framingRect.height() / getHeight()
                            );
                        }
                    }
                    return true;
            }
            return false;
        }

        // Определяет, какая ручка ближайшая к точке касания
        private Handle findHandle(float x, float y) {
            int l = framingRect.left, t = framingRect.top;
            int r = framingRect.right, b = framingRect.bottom;
            int cx = framingRect.centerX(), cy = framingRect.centerY();

            // Углы проверяем первыми — у них приоритет над краями
            if (near(x, y, l, t)) return Handle.TL;
            if (near(x, y, r, t)) return Handle.TR;
            if (near(x, y, l, b)) return Handle.BL;
            if (near(x, y, r, b)) return Handle.BR;

            if (near(x, y, cx, t)) return Handle.TOP;
            if (near(x, y, cx, b)) return Handle.BOTTOM;
            if (near(x, y, l, cy)) return Handle.LEFT;
            if (near(x, y, r, cy)) return Handle.RIGHT;

            return Handle.NONE;
        }

        private boolean near(float x, float y, float tx, float ty) {
            return Math.abs(x - tx) <= touchTarget && Math.abs(y - ty) <= touchTarget;
        }

        // Вычисляет новые границы рамки на основе смещения касания
        private void applyDrag(float dx, float dy) {
            int l = dragStartRect.left, t = dragStartRect.top;
            int r = dragStartRect.right, b = dragStartRect.bottom;
            int vw = getWidth(), vh = getHeight();

            switch (activeHandle) {
                case TL:     l += (int) dx; t += (int) dy; break;
                case TR:     r += (int) dx; t += (int) dy; break;
                case BL:     l += (int) dx; b += (int) dy; break;
                case BR:     r += (int) dx; b += (int) dy; break;
                case TOP:    t += (int) dy; break;
                case BOTTOM: b += (int) dy; break;
                case LEFT:   l += (int) dx; break;
                case RIGHT:  r += (int) dx; break;
            }

            // Не допускаем схлопывания области меньше минимального размера
            if (r - l < minSize) {
                boolean moveLeft = activeHandle == Handle.TL || activeHandle == Handle.BL || activeHandle == Handle.LEFT;
                if (moveLeft) l = r - minSize; else r = l + minSize;
            }
            if (b - t < minSize) {
                boolean moveTop = activeHandle == Handle.TL || activeHandle == Handle.TR || activeHandle == Handle.TOP;
                if (moveTop) t = b - minSize; else b = t + minSize;
            }

            // Не выходим за границы экрана
            framingRect.set(
                    Math.max(0, l),
                    Math.max(0, t),
                    Math.min(vw, r),
                    Math.min(vh, b)
            );
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (framingRect.isEmpty()) return;

            int vw = getWidth(), vh = getHeight();
            int l = framingRect.left, t = framingRect.top;
            int r = framingRect.right, b = framingRect.bottom;
            int cx = framingRect.centerX(), cy = framingRect.centerY();

            // Полупрозрачная маска снаружи области
            canvas.drawRect(0, 0, vw, t, maskPaint);
            canvas.drawRect(0, t, l, b, maskPaint);
            canvas.drawRect(r, t, vw, b, maskPaint);
            canvas.drawRect(0, b, vw, vh, maskPaint);

            // Тонкая рамка по периметру области
            canvas.drawRect(framingRect, borderPaint);

            // Жирные уголки — подсказка что за них можно тянуть
            int arm = cornerArm;
            canvas.drawLine(l, t, l + arm, t, cornerPaint);
            canvas.drawLine(l, t, l, t + arm, cornerPaint);

            canvas.drawLine(r - arm, t, r, t, cornerPaint);
            canvas.drawLine(r, t, r, t + arm, cornerPaint);

            canvas.drawLine(l, b, l + arm, b, cornerPaint);
            canvas.drawLine(l, b - arm, l, b, cornerPaint);

            canvas.drawLine(r - arm, b, r, b, cornerPaint);
            canvas.drawLine(r, b - arm, r, b, cornerPaint);

            // Круглые ручки по центру каждого края
            canvas.drawCircle(cx, t, handleRadius, handlePaint);
            canvas.drawCircle(cx, b, handleRadius, handlePaint);
            canvas.drawCircle(l, cy, handleRadius, handlePaint);
            canvas.drawCircle(r, cy, handleRadius, handlePaint);
        }

        private int dp(float value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    getResources().getDisplayMetrics());
        }
    }
}
