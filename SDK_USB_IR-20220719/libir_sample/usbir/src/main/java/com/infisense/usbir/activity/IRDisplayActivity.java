package com.infisense.usbir.activity;

import android.app.ProgressDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;
import android.yt.jni.Usbcontorl;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.infisense.iruvc.sdkisp.LibIRParse;
import com.infisense.iruvc.sdkisp.LibIRProcess;
import com.infisense.iruvc.sdkisp.LibIRTemp;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.CommonUtils;
import com.infisense.iruvc.utils.SynchronizedBitmap;
import com.infisense.usbir.R;
import com.infisense.usbir.adapter.PseudocolorAdapter;
import com.infisense.usbir.adapter.TempAdapter;
import com.infisense.usbir.bean.PseudocolorBean;
import com.infisense.usbir.bean.ReginModeBean;
import com.infisense.usbir.camera.IRUVC;
import com.infisense.usbir.thread.ImageThread;
import com.infisense.usbir.utils.BitmapUtils;
import com.infisense.usbir.utils.FileUtil;
import com.infisense.usbir.utils.ScreenUtils;
import com.infisense.usbir.view.CameraView;
import com.infisense.usbir.view.PopupCalibration;
import com.infisense.usbir.view.PopupImage;
import com.infisense.usbir.view.PopupOthers;
import com.infisense.usbir.view.PopupPseudocolor;
import com.infisense.usbir.view.PopupTemp;
import com.infisense.usbir.view.TemperatureView;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

import butterknife.BindView;
import butterknife.OnClick;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:48
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:48
 * @UpdateRemark:
 */
public class IRDisplayActivity extends BaseActivity implements PopupWindow.OnDismissListener {

    private static final String TAG = "IRDisplayActivity";
    @BindView(R.id.pseudocolorModeButton)
    TextView pseudocolorModeButton;
    @BindView(R.id.temperatureButton)
    TextView temperatureButton;
    @BindView(R.id.calibration)
    TextView calibration;
    @BindView(R.id.imageProcess)
    TextView imageProcess;
    @BindView(R.id.more)
    TextView more;
    @BindView(R.id.others)
    TextView others;
    @BindView(R.id.manualShutButton)
    ImageView manualShutButton;
    @BindView(R.id.captureImageButton)
    ImageView captureImageButton;
    @BindView(R.id.temperatureView)
    TemperatureView temperatureView;
    @BindView(R.id.cameraView)
    CameraView cameraView;
    @BindView(R.id.recordVideoButton)
    ImageView recordVideoButton;
    @BindView(R.id.shutterSwitch)
    ToggleButton shutterSwitch;

    private ImageThread imageThread;
    private Bitmap bitmap;
    private IRUVC iruvc;
    private int cameraWidth; // 传感器的原始宽度
    private int cameraHeight; // 传感器的原始高度
    private int tempHeight; // 温度数据高度
    private int imageWidth; // 经过旋转后的图像宽度
    private int imageHeight; // 经过旋转后的图像高度
    /**
     * 方式1：正常的出复合数据
     */
    private CommonParams.DataFlowMode defaultDataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
    // 是否使用IRISP算法集成
    private boolean isUseIRISP = false;

    /**
     * 方式2：TNR出图，通过ISP算法处理数据
     */
//    private CommonParams.DataFlowMode defaultDataFlowMode = CommonParams.DataFlowMode.TNR_OUTPUT;
//    // 是否使用IRISP算法集成
//    private boolean isUseIRISP = true;

    private byte[] imageSrc;
    private byte[] temperatureSrc;
    private PopupTemp popupTemp;
    private PopupPseudocolor popupPseudocolor;
    private PopupCalibration popupCalibration;
    private PopupImage popupImage;
    private PopupOthers popupOthers;
    private SynchronizedBitmap syncimage = new SynchronizedBitmap();
    private boolean isrun = false;

    private CommonParams.PseudoColorType pseudocolorMode = CommonParams.PseudoColorType.PSEUDO_WHITE_HOT;
    private boolean temperaturerun = false;
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
                restartUSBCamera();
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
        return R.layout.activity_ir_display;
    }

    @Override
    protected void init(Bundle savedInstanceState) {
        initDataFlowMode(defaultDataFlowMode);
        initdata();
    }

    @Override
    public void initView() {
        //=== temperatureSrc
        ArrayList<ReginModeBean> tList = new ArrayList<>();
        tList.add(new ReginModeBean(R.drawable.point, TemperatureView.REGION_MODE_POINT, getResources().getString(R.string.temp_point)));
        tList.add(new ReginModeBean(R.drawable.line, TemperatureView.REGION_MODE_LINE, getResources().getString(R.string.temp_line)));
        tList.add(new ReginModeBean(R.drawable.rect, TemperatureView.REGION_MODE_RECTANGLE, getResources().getString(R.string.temp_rect)));
        tList.add(new ReginModeBean(R.drawable.rectall, TemperatureView.REGION_MODE_CENTER, getResources().getString(R.string.temp_all)));
        tList.add(new ReginModeBean(R.drawable.rectall, TemperatureView.REGION_MODE_CLEAN, getResources().getString(R.string.temp_clear)));
        popupTemp = new PopupTemp(this, new TempAdapter(this, tList,
                new TempAdapter.OnItemOnclickListenter() {
                    @Override
                    public void onClick(int position) {
                        ReginModeBean imgBean = tList.get(position);
                        int mode = imgBean.getPcColor();
                        if (mode == 4) {
                            temperatureView.clear();
                            temperatureView.setVisibility(View.INVISIBLE);
                        } else {
                            temperatureView.setVisibility(View.VISIBLE);
                            temperatureView.setTemperatureRegionMode(mode);
                        }
                        popupTemp.dismiss();
                    }
                }), this);
        //=== pseudocolor
        ArrayList<PseudocolorBean> mList = new ArrayList<>();
        mList.add(new PseudocolorBean(R.drawable.add1, CommonParams.PseudoColorType.PSEUDO_WHITE_HOT, getResources().getString(R.string.color_p1)));
        // Deprecated
//        mList.add(new PseudocolorBean(R.drawable.add2, CommonParams.PseudoColorType.PSEUDO_RESERVED, getResources().getString(R.string.color_p12)));
        mList.add(new PseudocolorBean(R.drawable.add3, CommonParams.PseudoColorType.PSEUDO_IRON_RED, getResources().getString(R.string.color_p3)));
        mList.add(new PseudocolorBean(R.drawable.add4, CommonParams.PseudoColorType.PSEUDO_RAINBOW_1, getResources().getString(R.string.color_p4)));
        mList.add(new PseudocolorBean(R.drawable.add5, CommonParams.PseudoColorType.PSEUDO_RAINBOW_2, getResources().getString(R.string.color_p5)));
        mList.add(new PseudocolorBean(R.drawable.add6, CommonParams.PseudoColorType.PSEUDO_RAINBOW_3, getResources().getString(R.string.color_p6)));
        mList.add(new PseudocolorBean(R.drawable.add7, CommonParams.PseudoColorType.PSEUDO_RED_HOT, getResources().getString(R.string.color_p7)));
        mList.add(new PseudocolorBean(R.drawable.add8, CommonParams.PseudoColorType.PSEUDO_HOT_RED, getResources().getString(R.string.color_p8)));
        mList.add(new PseudocolorBean(R.drawable.add9, CommonParams.PseudoColorType.PSEUDO_RAINBOW_4, getResources().getString(R.string.color_p9)));
        mList.add(new PseudocolorBean(R.drawable.add10, CommonParams.PseudoColorType.PSEUDO_RAINBOW_5, getResources().getString(R.string.color_p10)));
        mList.add(new PseudocolorBean(R.drawable.add11, CommonParams.PseudoColorType.PSEUDO_BLACK_HOT, getResources().getString(R.string.color_p11)));
        popupPseudocolor = new PopupPseudocolor(this, new PseudocolorAdapter(this, mList,
                new PseudocolorAdapter.OnItemOnclickListenter() {
                    @Override
                    public void onClick(int position) {
                        PseudocolorBean imgBean = mList.get(position);
                        pseudocolorMode = imgBean.getPcColor();
                        /**
                         * 设置伪彩【set pseudocolor】
                         * 两种方式选择一种即可
                         */
                        // 方式1:固件机芯实现
//                        iruvc.getIrcmd().setPseudoColor(CommonParams.PreviewPathChannel.PREVIEW_PATH0, pseudocolorMode);
                        // 方式2:软件实现
                        imageThread.setPseudocolorMode(pseudocolorMode);
                        // get pseudocolor
                        byte[] colorType = new byte[1];
                        iruvc.getIrcmd().getPseudoColor(CommonParams.PreviewPathChannel.PREVIEW_PATH0, colorType);
                        Log.d(TAG, "getPseudoColor=" + colorType[0]);
                    }
                }), this);
        //===
        popupCalibration = new PopupCalibration(this, this);
        popupImage = new PopupImage(this, new PopupImage.OnRotateListener() {
            @Override
            public void onRotate(boolean isRotate) {
                setRotate(isRotate);
            }
        }, this);
        popupOthers = new PopupOthers(this, this);
        // 快门开合状态(立即生效，不可保存，故每次默认都为开)
        shutterSwitch.setChecked(true);
        shutterSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                //=== 快门开、快门合
                if (b) {
                    iruvc.getIrcmd().setShutterManualSwitch(CommonParams.ShutterManualSwitchType.SHUTTER_OPEN);
                } else {
                    iruvc.getIrcmd().setShutterManualSwitch(CommonParams.ShutterManualSwitchType.SHUTTER_CLOSE);
                }
            }
        });
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
        } else {
            /**
             * 图像
             */
            cameraWidth = 256;// 传感器的原始宽度
            cameraHeight = 192;// 传感器的原始高度
            tempHeight = 0;
        }
        imageWidth = cameraHeight - tempHeight;
        imageHeight = cameraWidth;

        imageSrc = new byte[imageWidth * imageHeight * 2];
        temperatureSrc = new byte[imageWidth * imageHeight * 2];
    }

    @Override
    public void onDismiss() {
        initTitleDisplay(0);
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
        temperatureView.setImageSize(imageWidth, imageHeight);
        temperatureView.setSyncimage(syncimage);
        temperatureView.setTemperature(temperatureSrc);
        temperatureView.setUseIRISP(isUseIRISP);
        temperatureView.setTemperatureRegionMode(TemperatureView.FOCUSABLES_TOUCH_MODE);
        temperatureView.setLayoutParams(fullScreenlayoutParams);
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
        iruvc = new IRUVC(cameraWidth, cameraHeight, IRDisplayActivity.this, syncimage,
                defaultDataFlowMode, isUseIRISP);
//        /**
//         * 调整带宽
//         * 部分分辨率或在部分机型上，会出现无法出图，或出图一段时间后卡顿的问题，需要配置对应的带宽
//         */
//        iruvc.getUvcCamera().setDefaultBandwidth(0.6F);
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
        popupCalibration.setIrcmd(iruvc.getIrcmd());
        popupImage.setIrcmd(iruvc.getIrcmd());
        popupOthers.setIrcmd(iruvc.getIrcmd());
        temperatureView.setIrcmd(iruvc.getIrcmd());
        // 画面旋转设置
        popupCalibration.setRotate(true);
        popupImage.setRotate(true);
    }

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        if (imageThread != null) imageThread.setRotate(rotate);
        if (iruvc != null) iruvc.setRotate(rotate);
        if (popupCalibration != null) popupCalibration.setRotate(rotate);
        if (popupImage != null) popupImage.setRotate(rotate);

        if (rotate) {
            imageThread.interrupt();
            bitmap = Bitmap.createBitmap(imageWidth, imageHeight, Bitmap.Config.ARGB_8888);
            temperatureView.setImageSize(imageWidth, imageHeight);
            try {
                imageThread.join();
            } catch (InterruptedException e) {
                Log.e(TAG, "imageThread.join(): catch an interrupted exception");
            }
            startISP();
        } else {
            bitmap = Bitmap.createBitmap(imageHeight, imageWidth, Bitmap.Config.ARGB_8888);
            temperatureView.setImageSize(imageHeight, imageWidth);
        }
        cameraView.setBitmap(bitmap);
        imageThread.setBitmap(bitmap);
    }

    /**
     * 重启设备
     */
    private void restartUSBCamera() {
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
        startUSB();
    }

    /**
     * deal title color display
     *
     * @param titleIndex
     */
    private void initTitleDisplay(int titleIndex) {
        pseudocolorModeButton.setTextColor(ContextCompat.getColor(this, R.color.white));
        temperatureButton.setTextColor(ContextCompat.getColor(this, R.color.white));
        calibration.setTextColor(ContextCompat.getColor(this, R.color.white));
        imageProcess.setTextColor(ContextCompat.getColor(this, R.color.white));
        more.setTextColor(ContextCompat.getColor(this, R.color.white));
        others.setTextColor(ContextCompat.getColor(this, R.color.white));
        switch (titleIndex) {
            case 1: {
                pseudocolorModeButton.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            case 2: {
                temperatureButton.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            case 3: {
                calibration.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            case 4: {
                imageProcess.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            case 5: {
                more.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            case 6: {
                others.setTextColor(ContextCompat.getColor(this, R.color.red));
                break;
            }
            default:
                break;
        }
    }

    @OnClick({R.id.pseudocolorModeButton, R.id.temperatureButton, R.id.manualShutButton, R.id.captureImageButton,
            R.id.recordVideoButton, R.id.imageProcess, R.id.more, R.id.others, R.id.calibration, R.id.btnShutterStatusSet})
    public void onViewClicked(View view) {
        char[] point = new char[2];
        point[0] = 100;//x
        point[1] = 23;//y
        switch (view.getId()) {
            case R.id.pseudocolorModeButton: {
                // 伪彩模式
                initTitleDisplay(1);
                popupPseudocolor.showAsDropDown(pseudocolorModeButton);
                break;
            }
            case R.id.temperatureButton: {
                // 温度测量
                initTitleDisplay(2);
                if (!temperaturerun) {
                    temperaturerun = true;
                    temperatureView.setVisibility(View.VISIBLE);
                }
                popupTemp.showAsDropDown(temperatureButton);
                break;
            }
            case R.id.calibration: {
                // 标定
                initTitleDisplay(3);
                popupCalibration.showAsDropDown(calibration);
                break;
            }
            case R.id.imageProcess: {
                // 图像参数
                initTitleDisplay(4);
                popupImage.showAsDropDown(imageProcess);
                break;
            }
            case R.id.more: {
                // 设备信息
                /**
                 * 修改SN信息
                 * P200020B18595709
                 */
                iruvc.getIrcmd().setSnInfo("P200020B18595708");
                /**
                 * 修改PN信息
                 * P2_C_V2.0_2080100020B18595709
                 */
                iruvc.getIrcmd().setPnInfo("P2_C_V2.0_2080100020B18595700");

                /**
                 * 写入OEM信息
                 */
                String oemWriteInfo = "1234567890123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890";
                Log.i(TAG, "oemWrite:" + iruvc.getIrcmd().oemWrite(oemWriteInfo.getBytes()) + " info:" + oemWriteInfo);
                /**
                 * 读取OEM信息
                 *
                 */
                byte[] oemInfo = new byte[oemWriteInfo.length()];
                iruvc.getIrcmd().oemRead(oemInfo);
                Log.i(TAG, "oemRead:" + new String(oemInfo));

                //
                initTitleDisplay(5);
                byte[] CHIP_FW_INFO = new byte[8];
                byte[] FW_COMPILE_DATE = new byte[8];
                byte[] DEV_QUALIFICATION = new byte[8];
                byte[] IR_INFO = new byte[26];
                byte[] PROJECT_INFO = new byte[4];
                byte[] FW_BUILD_VERSION_INFO = new byte[50];
                byte[] PN = new byte[48];
                byte[] SN = new byte[16];
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_CHIP_ID, CHIP_FW_INFO); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_FW_COMPILE_DATE, FW_COMPILE_DATE); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_DEV_QUALIFICATION, DEV_QUALIFICATION); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_PROJECT_INFO, PROJECT_INFO); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_IR_INFO, IR_INFO); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_FW_BUILD_VERSION_INFO, FW_BUILD_VERSION_INFO); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_GET_PN, PN); //ok
                iruvc.getIrcmd().getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_GET_SN, SN); //ok
                Log.d(TAG, "CHIP_FW_INFO:" + new String(CHIP_FW_INFO));
                Log.d(TAG, "FW_COMPILE_DATE:" + new String(FW_COMPILE_DATE));
                Log.d(TAG, "DEV_QUALIFICATION:" + new String(DEV_QUALIFICATION));
                Log.d(TAG, "PROJECT_INFO:" + new String(PROJECT_INFO));
                Log.d(TAG, "IR_INFO:" + new String(IR_INFO));
                Log.d(TAG, "FW_BUILD_VERSION_INFO:" + new String(FW_BUILD_VERSION_INFO));
                Log.d(TAG, "PN:" + new String(PN));
                Log.d(TAG, "SN:" + new String(SN));
                String info = "Version:";
                try {
                    info += getPackageManager().getPackageInfo(
                            getPackageName(), 0).versionName;
                } catch (PackageManager.NameNotFoundException e) {
                    e.printStackTrace();
                }
                info += String.format(Locale.US, "\nPROJECT_INFO:%02d.%02d.%02d.%02d", PROJECT_INFO[1] & 0xff, PROJECT_INFO[0] & 0xff, PROJECT_INFO[2] & 0xff, PROJECT_INFO[3] & 0xff);
                info += "\nPN:" + new String(PN);
                info += "\n" + CommonUtils.getPNInfo("P2STDMD25602011XHWRXX-1170100010");
                info += "\nSN:" + new String(SN);
                info += "\n" + CommonUtils.getSNInfo("YMN32091XD032200001");
                info += String.format(Locale.US, "\nIR_INFO:");
                for (int i = 0; i < 7; i++) {
                    info += String.format(Locale.US, "%02d", IR_INFO[i] & 0xff);
                }
                info += "\nIRCMDVer:" + iruvc.getIrcmd().getIRCMDVersion();
                info += "\nIRTempVer:" + LibIRTemp.getIRTempVersion();
                info += "\nIRProcessVer:" + LibIRProcess.getIRProcessVersion();
                info += "\nIRParseVer:" + LibIRParse.getIRParseVersion();
                info += "\nSupportedSize:" + iruvc.getUvcCamera().getSupportedSize();
                ScreenUtils.showNormalDialog(this, info, this);
                break;
            }
            case R.id.others: {
                // 其它
                initTitleDisplay(6);
                popupOthers.showAsDropDown(others);
                break;
            }
            case R.id.manualShutButton: {
                //=== 打快门
                if (syncimage.type == 1) {
                    iruvc.getIrcmd().tiny1bShutterManual();
                } else {
                    iruvc.getIrcmd().updateOOCOrB(CommonParams.UpdateOOCOrBType.B_UPDATE);
                }
                // 打快门之后快门的开合状态会变为打开
                shutterSwitch.setChecked(true);
                break;
            }
            case R.id.btnShutterStatusSet: {
                //=== 打背景
                // 打背景即在快门开的时候关闭使能，再调用打快门
                // 1. 开快门
                iruvc.getIrcmd().setShutterManualSwitch(CommonParams.ShutterManualSwitchType.SHUTTER_OPEN);
                // 2. 关闭快门使能
                iruvc.getIrcmd().setShutterStatus(CommonParams.ShutterStatus.SHUTTER_CTL_DIS);
                // 3. 打快门
                if (syncimage.type == 1) {
                    iruvc.getIrcmd().tiny1bShutterManual();
                } else {
                    iruvc.getIrcmd().updateOOCOrB(CommonParams.UpdateOOCOrBType.B_UPDATE);
                }
                // 打快门之后快门的开合状态会变为打开
                shutterSwitch.setChecked(true);
                // 恢复方法：1. 打开快门使能;2. 打快门
                break;
            }
            case R.id.captureImageButton: {
                //=== 拍照
                // 获取展示图像信息的图层数据
                Bitmap cameraViewBitmap = cameraView.getBitmap();
                // 获取温度图层的数据，包括点线框，温度值等，重新合成bitmap
                cameraViewBitmap = BitmapUtils.mergeBitmap(cameraViewBitmap, temperatureView.getRegionAndValueBitmap(), 0, 0);
                // 保存图片
                File pictureFile = BitmapUtils.saveBmp2Gallery(IRDisplayActivity.this, cameraViewBitmap,
                        System.currentTimeMillis() + "");
                Log.d(TAG, "getPath = " + pictureFile.getPath());
                break;
            }
            case R.id.recordVideoButton: {
                // 保存红外和温度数据到文件中
                FileUtil.savaRawFile(imageSrc, temperatureSrc);
                Toast.makeText(this, getResources().getString(R.string.file_save_success), Toast.LENGTH_SHORT).show();
                break;
            }
            default:
                break;
        }
    }

    @Override
    protected void onStart() {
        Log.w(TAG, "onStart");
        super.onStart();
        if (!isrun) {
            startUSB();
            startISP();
            temperatureView.start();
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
        temperatureView.stop();
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