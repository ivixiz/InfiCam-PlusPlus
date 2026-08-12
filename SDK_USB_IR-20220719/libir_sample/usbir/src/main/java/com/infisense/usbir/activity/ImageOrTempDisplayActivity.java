package com.infisense.usbir.activity;

import android.app.ProgressDialog;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.yt.jni.Usbcontorl;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.SynchronizedBitmap;
import com.infisense.usbir.R;
import com.infisense.usbir.camera.IRUVC;
import com.infisense.usbir.thread.ImageThread;
import com.infisense.usbir.utils.FileUtil;
import com.infisense.usbir.utils.ScreenUtils;
import com.infisense.usbir.view.CameraView;

import butterknife.BindView;
import butterknife.OnClick;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:47
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:47
 * @UpdateRemark:
 */
public class ImageOrTempDisplayActivity extends BaseActivity {

    private static final String TAG = "ImageOrTempDisplayActivity";
    @BindView(R.id.cameraView)
    CameraView cameraView;
    @BindView(R.id.btnImageTemp)
    Button btnImageTemp;
    @BindView(R.id.btnImage)
    Button btnImage;
    @BindView(R.id.btnTemp)
    Button btnTemp;

    private ImageThread imageThread;
    private Bitmap bitmap;
    private IRUVC iruvc;
    private CommonParams.DataFlowMode defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
    // 是否使用IRISP算法集成
    private boolean isUseIRISP = false;

    private int cameraWidth; // 传感器的原始宽度
    private int cameraHeight;// 传感器的原始高度
    private int tempHeight; // 温度数据高度
    private int imageWidth; // 经过旋转后的图像宽度
    private int imageHeight; // 经过旋转后的图像高度

    private byte[] imageSrc;
    private byte[] temperatureSrc;
    private SynchronizedBitmap syncimage = new SynchronizedBitmap();
    private boolean isrun = false;

    private CommonParams.PseudoColorType pseudocolorMode = CommonParams.PseudoColorType.PSEUDO_WHITE_HOT;
    private RelativeLayout.LayoutParams fullScreenlayoutParams;

    // progressDialog
    private ProgressDialog progressDialog;

    //
    public static final int RESTART_USB = 1000;
    public static final int Y16_START_MSG = 1001;
    public static final int YUV_STOP_MSG = 1002;
    public static final int YUV_START_MSG = 1003;
    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == RESTART_USB) {
                restartusbcamera();
            } else if (msg.what == Y16_START_MSG) {
                iruvc.getIrcmd().startY16ModePreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                        FileUtil.getY16SrcTypeByDataFlowMode(defaultDataFlowMode));
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                iruvc.setFrameReady(true);
                // 出图之后再去获取kt,bt,nuc_t等参数来设置温度数据，避免耗时操作导致这里的停图和出图受影响
                iruvc.getIRISPfParamData();
            } else if (msg.what == YUV_STOP_MSG) {
                iruvc.getIrcmd().stopPreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0);
                mHandler.sendEmptyMessageDelayed(YUV_START_MSG, 2000);
            } else if (msg.what == YUV_START_MSG) {
                iruvc.getIrcmd().startPreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.StartPreviewSource.SOURCE_SENSOR,
                        25, CommonParams.StartPreviewMode.VOC_DVP_MODE, defaultDataFlowMode);
                if (defaultDataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT ||
                        defaultDataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
                    if (progressDialog != null && progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                } else {
                    // 需要延时发送命令
                    mHandler.sendEmptyMessageDelayed(Y16_START_MSG, 2000);
                }
            }
        }
    };

    @Override
    protected int getContentView() {
        return R.layout.activity_image_or_temp_display;
    }

    @Override
    public void initView() {
        findViewById(R.id.btnStopYUV).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 通过ircmd命令停图
                iruvc.getIrcmd().stopPreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0);
            }
        });
        findViewById(R.id.btnStartYUV).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 通过ircmd命令出图
                iruvc.getIrcmd().startPreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.StartPreviewSource.SOURCE_SENSOR,
                        25, CommonParams.StartPreviewMode.VOC_DVP_MODE, defaultDataFlowMode);
            }
        });
        findViewById(R.id.btnStopY16).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iruvc.getIrcmd().stopY16ModePreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0);
            }
        });
        findViewById(R.id.btnStartY16).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                iruvc.getIrcmd().startY16ModePreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0,
                        FileUtil.getY16SrcTypeByDataFlowMode(defaultDataFlowMode));
            }
        });
    }

    @Override
    protected void init(Bundle savedInstanceState) {
        initDataFlowMode(defaultDataFlowMode);
        initdata();
    }

    /**
     * @param dataFlowMode
     */
    private void initDataFlowMode(CommonParams.DataFlowMode dataFlowMode) {
        if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT) {
            /**
             * 图像+温度
             */
            cameraWidth = 256; // 传感器的原始宽度
            cameraHeight = 384; // 传感器的原始高度
            tempHeight = 192;
            //
            btnImageTemp.setTextColor(ContextCompat.getColor(this,R.color.red));
            btnImage.setTextColor(ContextCompat.getColor(this,R.color.black));
            btnTemp.setTextColor(ContextCompat.getColor(this,R.color.black));
        } else if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
            /**
             * 图像
             */
            cameraWidth = 256;// 传感器的原始宽度
            cameraHeight = 192;// 传感器的原始高度
            tempHeight = 0;
            //
            btnImageTemp.setTextColor(ContextCompat.getColor(this,R.color.black));
            btnImage.setTextColor(ContextCompat.getColor(this,R.color.red));
            btnTemp.setTextColor(ContextCompat.getColor(this,R.color.black));
        } else {
            /**
             * 温度
             */
            cameraWidth = 256;// 传感器的原始宽度
            cameraHeight = 192;// 传感器的原始高度
            tempHeight = 0;
            //
            btnImageTemp.setTextColor(ContextCompat.getColor(this,R.color.black));
            btnImage.setTextColor(ContextCompat.getColor(this,R.color.black));
            btnTemp.setTextColor(ContextCompat.getColor(this,R.color.red));
        }
        imageWidth = cameraHeight - tempHeight;
        imageHeight = cameraWidth;

        imageSrc = new byte[imageWidth * imageHeight * 2];
        temperatureSrc = new byte[imageWidth * imageHeight * 2];
    }

    /**
     *
     */
    private void initdata() {
        // 计算画面的宽高，避免被拉伸变形
        int screenWidth = ScreenUtils.getScreenWidth(this);
        fullScreenlayoutParams = new RelativeLayout.LayoutParams(screenWidth,
                imageHeight * screenWidth / imageWidth);
        fullScreenlayoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);
        //
        bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
        cameraView.setSyncimage(syncimage);
        cameraView.setBitmap(bitmap);
        cameraView.setLayoutParams(fullScreenlayoutParams);
        // 某些特定客户的特殊设备需要使用该命令关闭或打开sensor的供电
        if (Usbcontorl.isLoad) {
            Usbcontorl.setUSB3803Mode(true);//打开sensor供电
        }
    }

    /**
     *
     */
    private void startISP() {
        imageThread = new ImageThread(imageWidth, imageHeight);
        imageThread.setDataFlowMode(defaultDataFlowMode);
        imageThread.setSyncimage(syncimage);
        imageThread.setImagesrc(imageSrc);
        imageThread.setBitmap(bitmap);
        imageThread.setRotate(true);
        imageThread.setPseudocolorMode(pseudocolorMode);
        imageThread.start();
    }

    /**
     *
     */
    private void startUSB() {
        iruvc = new IRUVC(cameraWidth, cameraHeight, ImageOrTempDisplayActivity.this, syncimage,
                defaultDataFlowMode, isUseIRISP);
        //        /**
//         * 调整带宽
//         * 部分分辨率或在部分机型上，会出现无法出图，或出图一段时间后卡顿的问题，需要配置对应的带宽
//         */
//        iruvc.getUvcCamera().setDefaultBandwidth(0.5F);
        iruvc.setImageSrc(imageSrc);
        iruvc.setTemperatureSrc(temperatureSrc);
        iruvc.setRotate(true);
        iruvc.setmHandler(mHandler);
        iruvc.registerUSB();
        /**
         * 处理初始化之后的数据流模式
         */
        if (defaultDataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT ||
                defaultDataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
            /**
             * 默认上电之后出YUV图像，如果之前为Y16中间出图模式且没有断电，则重新进入之后需要停Y16模式
             */
            iruvc.getIrcmd().stopY16ModePreview(CommonParams.PreviewPathChannel.PREVIEW_PATH0);
        } else {
            // 画面尚未准备完毕，此时可以使用loading页面或自定义页面来遮挡
            iruvc.setFrameReady(false);
            /**
             * 默认上电之后出YUV图像，如果默认模式为Y16中间出图，进入之后需要走先断电再上电，再中间出图的流程
             * 如果没有断电，且之前的模式为Y16模式，则重新进入仍为Y16模式，不需要执行该流程
             */
            progressDialog = ProgressDialog.show(this, "", "loading", true);
            // 调用 startY16ModePreview 中间出图方法之后，输出的数据格式为y16
            mHandler.sendEmptyMessageDelayed(YUV_STOP_MSG, 3000);
        }
    }

    /**
     *
     */
    private void restartusbcamera() {
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
        startUSB();
    }

    @OnClick({R.id.btnImageTemp, R.id.btnImage, R.id.btnTemp})
    public void onViewClicked(View view) {
        //
        if (view.getId() == R.id.btnImageTemp) {
            /**
             * 图像+温度
             */
            defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
        } else if (view.getId() == R.id.btnImage) {
            /**
             * 图像
             */
            defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_OUTPUT;
        } else if (view.getId() == R.id.btnTemp) {
            /**
             * 温度
             */
            defaultDataFlowMode = CommonParams.DataFlowMode.TEMP_OUTPUT;
        }
        initDataFlowMode(defaultDataFlowMode);
        imageThread.setDataFlowMode(defaultDataFlowMode);
        progressDialog = ProgressDialog.show(this, "", "loading", true);
        //
        mHandler.sendEmptyMessageDelayed(YUV_STOP_MSG, 2000);
    }

    @Override
    protected void onStart() {
        Log.w(TAG, "onStart");
        super.onStart();
        if (!isrun) {
            startUSB();
            startISP();
            cameraView.start();
            isrun = true;
        }
    }

    @Override
    protected void onStop() {
        Log.w(TAG, "onStop");
        super.onStop();
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
        imageThread.interrupt();
        syncimage.valid = false;
        cameraView.stop();
        isrun = false;
    }

    @Override
    protected void onDestroy() {
        Log.w(TAG, "onDestroy");
        super.onDestroy();
        try {
            imageThread.join();
        } catch (InterruptedException e) {
            Log.e(TAG, "imageThread.join(): catch an interrupted exception");
        }
        // 某些特定客户的特殊设备需要使用该命令关闭或打开sensor的供电
        if (Usbcontorl.isLoad) {
            Usbcontorl.setUSB3803Mode(false);//关闭sensor供电
        }
    }
}