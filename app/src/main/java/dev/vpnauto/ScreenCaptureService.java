package dev.vpnauto;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-shot flow:
 *   hold MediaProjection -> bring Authenticator to front -> capture one frame
 *   -> OCR -> extract 6-digit TOTP codes -> hand results to MainActivity.
 *
 * Note: if Authenticator sets FLAG_SECURE the frame will be black and OCR
 * finds nothing; that outcome is logged explicitly.
 */
public class ScreenCaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_DATA = "data";
    public static final String EXTRA_CODES = "codes";

    private static final String CHANNEL_ID = "capture";
    private static final int NOTIFICATION_ID = 1;
    private static final String AUTH_PACKAGE = "com.azure.authenticator";
    private static final long CAPTURE_DELAY_MS = 2000;

    // Matches "123456" and "123 456" (Authenticator groups digits with a space).
    private static final Pattern TOTP =
            Pattern.compile("(?<!\\d)(\\d{3})[ ]?(\\d{3})(?!\\d)");

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private boolean stopped = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        LogBus.log("抓屏服务启动");
        startForegroundWithNotification();

        int resultCode = intent != null
                ? intent.getIntExtra(EXTRA_RESULT_CODE, 0) : 0;
        Intent data = intent != null ? intent.getParcelableExtra(EXTRA_DATA) : null;
        if (resultCode == 0 || data == null) {
            LogBus.log("错误: 缺少 MediaProjection 授权数据");
            deliver(new ArrayList<>());
            return START_NOT_STICKY;
        }

        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = mpm.getMediaProjection(resultCode, data);
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                LogBus.log("MediaProjection 已停止");
            }
        }, handler);

        launchAuthenticator();
        handler.postDelayed(this::captureOnce, CAPTURE_DELAY_MS);
        return START_NOT_STICKY;
    }

    private void startForegroundWithNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "屏幕抓取", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("VPN Auto 正在抓取屏幕")
                .setContentText("用于读取 Authenticator 验证码")
                .setContentIntent(pi)
                .build();
        startForeground(NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void launchAuthenticator() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(AUTH_PACKAGE);
        if (launch == null) {
            LogBus.log("错误: 未找到 Microsoft Authenticator (" + AUTH_PACKAGE + ")");
            deliver(new ArrayList<>());
            return;
        }
        LogBus.log("拉起 Authenticator，" + CAPTURE_DELAY_MS + "ms 后截屏");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(launch);
    }

    private void captureOnce() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        int width, height;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect b = wm.getMaximumWindowMetrics().getBounds();
            width = b.width();
            height = b.height();
        } else {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            width = dm.widthPixels;
            height = dm.heightPixels;
        }
        int dpi = getResources().getDisplayMetrics().densityDpi;
        LogBus.log("截屏 " + width + "x" + height + " @" + dpi + "dpi");

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        virtualDisplay = projection.createVirtualDisplay(
                "vpn-auto-capture", width, height, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, handler);

        // Give the virtual display a moment to produce a frame.
        handler.postDelayed(this::readFrame, 500);
    }

    private void readFrame() {
        Bitmap bitmap = null;
        try (Image image = imageReader.acquireLatestImage()) {
            if (image == null) {
                LogBus.log("错误: 未获取到画面帧");
                deliver(new ArrayList<>());
                return;
            }
            bitmap = toBitmap(image);
        } finally {
            stopCapture();
        }
        if (bitmap == null) {
            LogBus.log("错误: 画面帧转换失败");
            deliver(new ArrayList<>());
            return;
        }
        LogBus.log("抓帧成功，开始 OCR");
        runOcr(bitmap);
    }

    private static Bitmap toBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap raw = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(), Bitmap.Config.ARGB_8888);
        raw.copyPixelsFromBuffer(buffer);
        return Bitmap.createBitmap(raw, 0, 0, image.getWidth(), image.getHeight());
    }

    private void runOcr(Bitmap bitmap) {
        TextRecognizer recognizer =
                TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener(text -> deliver(extractCodes(text)))
                .addOnFailureListener(e -> {
                    LogBus.log("OCR 失败: " + e.getMessage()
                            + "（首次使用需在线下载识别模型，请确认网络后重试）");
                    deliver(new ArrayList<>());
                });
    }

    private static ArrayList<String> extractCodes(Text text) {
        LinkedHashSet<String> codes = new LinkedHashSet<>();
        for (Text.TextBlock block : text.getTextBlocks()) {
            Matcher m = TOTP.matcher(block.getText());
            while (m.find()) {
                codes.add(m.group(1) + m.group(2));
            }
        }
        LogBus.log("OCR 完成，识别到 " + codes.size() + " 个候选验证码");
        return new ArrayList<>(codes);
    }

    private void stopCapture() {
        if (stopped) return;
        stopped = true;
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (projection != null) projection.stop();
    }

    private void deliver(ArrayList<String> codes) {
        stopCapture();
        Intent open = new Intent(this, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        open.putStringArrayListExtra(EXTRA_CODES, codes);
        startActivity(open);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        stopCapture();
        super.onDestroy();
    }
}
