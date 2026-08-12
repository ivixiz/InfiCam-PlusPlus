package com.infisense.usbir.view;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;


import com.infisense.iruvc.utils.SynchronizedBitmap;

/**
 * 红外图像展示控件，可以为TextureView或SurfaceView
 */
public class CameraView extends TextureView {
    private String TAG = "CameraView";
    private Bitmap bitmap;
    private SynchronizedBitmap syncimage;
    private Runnable runnable;
    private Thread cameraThread;
    private Canvas canvas = null;

    public CameraView(Context context) {
        this(context, null, 0);
    }

    public CameraView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /**
     * @param context
     * @param attrs
     * @param defStyleAttr
     */
    public CameraView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        // 线程中绘制画面
        runnable = new Runnable() {
            @Override
            public void run() {
                while (!cameraThread.isInterrupted()) {
                    synchronized (syncimage.viewLock) {
                        //
                        if (syncimage.valid == false) {
                            try {
                                syncimage.viewLock.wait();
                            } catch (InterruptedException e) {
                                cameraThread.interrupt();
                                Log.e(TAG, "lock.wait(): catch an interrupted exception");
                            }
                        }
                        //
                        if (syncimage.valid == true) {
                            canvas = lockCanvas();
                            if (canvas == null)
                                continue;

                            /**
                             * 图片缩放，这里简单的使用getWidth()作为宽，getHeight()作为高，可能会出现画面拉伸情况，
                             * 实际使用的时候请参考设备的宽高按照设备的图像尺寸做等比例缩放
                             */
                            Bitmap mScaledBitmap = Bitmap.createScaledBitmap(bitmap, getWidth(), getHeight(), true);
                            canvas.drawBitmap(mScaledBitmap, 0, 0, null);

                            // 画面中心的十字交叉线绘制
                            Paint paint = new Paint();  //画笔
                            paint.setStrokeWidth(2);  //设置线宽。单位为像素
                            paint.setAntiAlias(true); //抗锯齿
                            paint.setDither(true);    //防抖动
                            paint.setColor(Color.WHITE);  //画笔颜色

                            int cross_len = 20;
                            canvas.drawLine(getWidth() / 2 - cross_len, getHeight() / 2,
                                    getWidth() / 2 + cross_len, getHeight() / 2, paint);
                            canvas.drawLine(getWidth() / 2, getHeight() / 2 - cross_len,
                                    getWidth() / 2, getHeight() / 2 + cross_len, paint);
                            //
                            unlockCanvasAndPost(canvas);
                            syncimage.valid = false;
                        }
                    }
                    SystemClock.sleep(1);
                }
                Log.w(TAG, "DisplayThread exit:");
            }
        };
    }

    /**
     * @param bitmap
     */
    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    /**
     * @param syncimage
     */
    public void setSyncimage(SynchronizedBitmap syncimage) {
        this.syncimage = syncimage;
    }

    /**
     *
     */
    public void start() {
        cameraThread = new Thread(runnable);
        cameraThread.start();
    }

    /**
     *
     */
    public void stop() {
        cameraThread.interrupt();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}




