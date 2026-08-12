package com.infisense.usbir.camera;

import android.content.Context;
import android.content.res.AssetManager;
import android.hardware.usb.UsbDevice;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.infisense.iruvc.ircmd.ConcreteIRCMDBuilder;
import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.ircmd.IRCMDType;
import com.infisense.iruvc.sdkisp.LibIRProcess;
import com.infisense.iruvc.usb.DeviceFilter;
import com.infisense.iruvc.usb.USBMonitor;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.IFrameCallback;
import com.infisense.iruvc.utils.SynchronizedBitmap;
import com.infisense.iruvc.uvc.ConcreateUVCBuilder;
import com.infisense.iruvc.uvc.UVCCamera;
import com.infisense.iruvc.uvc.UVCType;
import com.infisense.usbir.R;
import com.infisense.usbir.activity.IRDisplayActivity;
import com.infisense.usbir.utils.FileUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

/*
 * @Description:    红外出图核心工具类
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 15:36
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 15:36
 * @UpdateRemark:
 */
public class IRUVC {

    private static final String TAG = "IRUVC";
    private final int TinyB = 0x3901;
    private IFrameCallback iFrameCallback;
    private Context mContext;
    private UVCCamera uvcCamera;
    private IRCMD ircmd;
    // 机芯温度
    private int[] curVtemp = new int[1];
    //
    private USBMonitor mUSBMonitor;
    private int cameraWidth;
    private int cameraHeight;
    private byte[] imageSrc;
    private byte[] temperatureSrc;
    private SynchronizedBitmap syncimage;
    // 设备PID白名单
    private int pids[] = {0x5840, 0x3901, 0x5830, 0x5838};
    private boolean auto_gain_switch = true;
    private boolean auto_over_portect = true;
    private LibIRProcess.AutoGainSwitchInfo_t auto_gain_switch_info = new LibIRProcess.AutoGainSwitchInfo_t();
    private LibIRProcess.GainSwitchParam_t gain_switch_param = new LibIRProcess.GainSwitchParam_t();
    // 帧率展示
    private int count = 0;
    private long timestart = 0;
    private double fps = 0;
    //
    private Handler mHandler;
    private boolean rotate = false;
    // 是否使用IRISP算法集成
    private boolean isUseIRISP;
    // 判断数据是否准备完毕，在准备完毕之前，画面可能会出现不正常
    private boolean isFrameReady = true;
    // 当前的增益状态
    private CommonParams.GainStatus gainStatus = CommonParams.GainStatus.HIGH_GAIN;
    // 模组支持的高低增益模式
    private CommonParams.GainMode gainMode = CommonParams.GainMode.GAIN_MODE_HIGH_LOW;
    private short[] nuc_table_high = new short[8192];
    private short[] nuc_table_low = new short[8192];
    private boolean isReadNuc = true; // 是否读取nuc信息，首次读取nuc信息，后续存储到文件中下次直接传入不用重新读取
    //
    private byte[] priv_high = new byte[1201];
    private byte[] priv_low = new byte[1201];
    private short[] kt_high = new short[1201];
    private short[] kt_low = new short[1201];
    private short[] bt_high = new short[1201];
    private short[] bt_low = new short[1201];

    /**
     * @param cameraHeight
     * @param cameraWidth
     * @param context
     * @param syncimage
     * @param dataFlowMode
     */
    public IRUVC(int cameraWidth, int cameraHeight, Context context, SynchronizedBitmap syncimage,
                 CommonParams.DataFlowMode dataFlowMode, boolean isUseIRISP) {
        this.cameraHeight = cameraHeight;
        this.cameraWidth = cameraWidth;
        this.mContext = context;
        this.syncimage = syncimage;
        this.isUseIRISP = isUseIRISP;
        //
        init(cameraWidth, cameraHeight);
        // 注意：USBMonitor的所有回调函数都是运行在线程中的
        mUSBMonitor = new USBMonitor(context, new USBMonitor.OnDeviceConnectListener() {

            // called by checking usb device
            // do request device permission
            @Override
            public void onAttach(UsbDevice device) {
                Log.w(TAG, "onAttach");
                if (isIRpid(device.getProductId())) {
                    if (uvcCamera == null || !uvcCamera.getOpenStatus()) {
                        mUSBMonitor.requestPermission(device);
                    }
                }
            }

            // called by connect to usb camera
            // do open camera,start previewing
            @Override
            public void onConnect(final UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                Log.w(TAG, "onConnect");
                if (isIRpid(device.getProductId())) {
                    if (createNew) {
                        openUVCCamera(ctrlBlock, dataFlowMode);
                        startPreview();
                    }
                }
            }

            // called by disconnect to usb camera
            // do nothing
            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                Log.w(TAG, "onDisconnect");
            }

            // called by taking out usb device
            // do close camera
            @Override
            public void onDettach(UsbDevice device) {
                Log.w(TAG, "onDettach");
                if (isIRpid(device.getProductId())) {
                    if (uvcCamera != null && uvcCamera.getOpenStatus()) {
                        stopPreview();
                    }
                }
            }

            @Override
            public void onCancel(UsbDevice device) {
                Log.w(TAG, "onCancel");
            }
        });
        /**
         * 同时打开防灼烧和自动增益切换后，如果想修改防灼烧和自动增益切换的触发优先级，可以通过修改下面的触发参数实现
         */
        // 自动增益切换参数auto gain switch parameter
        gain_switch_param.above_pixel_prop = 0.1f;    //用于high -> low gain,设备像素总面积的百分比
        gain_switch_param.above_temp_data = (int) ((130 + 273.15) * 16 * 4); //用于high -> low gain,高增益向低增益切换的触发温度
        gain_switch_param.below_pixel_prop = 0.95f;   //用于low -> high gain,设备像素总面积的百分比
        gain_switch_param.below_temp_data = (int) ((110 + 273.15) * 16 * 4);//用于low -> high gain,低增益向高增益切换的触发温度
        auto_gain_switch_info.switch_frame_cnt = 5 * 15; //连续满足触发条件帧数超过该阈值会触发自动增益切换(假设出图速度为15帧每秒，则5 * 15大概为5秒)
        auto_gain_switch_info.waiting_frame_cnt = 7 * 15;//触发自动增益切换之后，会间隔该阈值的帧数不进行增益切换监测(假设出图速度为15帧每秒，则7 * 15大概为7秒)
        // 防灼烧参数over_portect parameter
        int low_gain_over_temp_data = (int) ((550 + 273.15) * 16 * 4); //低增益下触发防灼烧的温度
        int high_gain_over_temp_data = (int) ((150 + 273.15) * 16 * 4); //高增益下触发防灼烧的温度
        float pixel_above_prop = 0.02f;//设备像素总面积的百分比
        int switch_frame_cnt = 7 * 15;//连续满足触发条件超过该阈值会触发防灼烧(假设出图速度为15帧每秒，则7 * 15大概为7秒)
        int close_frame_cnt = 10 * 15;//触发防灼烧之后，经过该阈值的帧数之后会解除防灼烧(假设出图速度为15帧每秒，则10 * 15大概为10秒)
        // 设备出图回调
        iFrameCallback = new IFrameCallback() {
            @Override
            public void onFrame(byte[] frame) {
                if (!isFrameReady) {
                    return;
                }
                // 帧率展示
                count++;
                if (count == 100) {
                    count = 0;
                    long currentTimeMillis = System.currentTimeMillis();
                    if (timestart != 0) {
                        long timeuse = currentTimeMillis - timestart;
                        fps = 100 * 1000 / (timeuse + 0.0);
                    }
                    timestart = currentTimeMillis;
                    Log.d(TAG, "frame.length = " + frame.length + " fps=" + String.format(Locale.US,"%.1f", fps) +
                            " dataFlowMode = " + dataFlowMode + " rotate = " + rotate + " isUseIRISP = " + isUseIRISP);
                }
                //
                if (syncimage == null) return;
                syncimage.start = true;
                //
                synchronized (syncimage.dataLock) {
                    // 判断坏帧，出现坏帧则重启sensor
                    int length = frame.length - 1;
                    if (frame[length] == 1) {
                        // bad frame
                        if (mHandler != null)
                            mHandler.sendEmptyMessage(IRDisplayActivity.RESTART_USB);
                        Log.d(TAG, "RESTART_USB");
                        return;
                    }
                    if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT || isUseIRISP) {
                        /**
                         * 图像+温度
                         */
                        /**
                         * copy红外数据到image数组中
                         * 出图的frame数组中前半部分是红外数据，后半部分是温度数据，
                         * 例如256*384分辨率的设备，前面的256*192是红外数据，后面的256*192是温度数据，
                         * 其中的数据是旋转90度的，需要旋转回来。
                         */
                        System.arraycopy(frame, 0, imageSrc, 0, length / 2);
                        //=== 画面旋转，温度数据需要跟着旋转
                        LibIRProcess.ImageRes_t imageRes = new LibIRProcess.ImageRes_t();
                        imageRes.height = (char) (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT ? cameraHeight / 2 : cameraHeight);
                        imageRes.width = (char) cameraWidth;
                        if (rotate) {
                            byte[] temp = new byte[length / 2];
                            System.arraycopy(frame, length / 2, temp, 0, length / 2);
                            LibIRProcess.rotateRight90(temp, imageRes, CommonParams.IRPROCSRCFMTType.IRPROC_SRC_FMT_Y14, temperatureSrc);
                        } else {
                            System.arraycopy(frame, length / 2, temperatureSrc, 0, length / 2);
                        }

                        // 自动增益切换，不生效的话请您的设备是否支持自动增益切换
                        if (auto_gain_switch) {
                            ircmd.autoGainSwitch(temperatureSrc, imageRes, auto_gain_switch_info,
                                    gain_switch_param, new IRCMD.AutoGainSwitchCallback() {
                                        @Override
                                        public void onAutoGainSwitchState(CommonParams.PropTPDParamsValue.GAINSELStatus gainselStatus) {

                                        }

                                        @Override
                                        public void onAutoGainSwitchResult(CommonParams.PropTPDParamsValue.GAINSELStatus gainselStatus, int result) {

                                        }
                                    });
                        }
                        // 防灼烧保护
                        if (auto_over_portect) {
                            ircmd.avoidOverexposure(gainStatus, temperatureSrc, imageRes, low_gain_over_temp_data,
                                    high_gain_over_temp_data, pixel_above_prop, switch_frame_cnt, close_frame_cnt, new IRCMD.AvoidOverexposureCallback() {
                                        @Override
                                        public void onAvoidOverexposureState(boolean avoidOverexpol) {

                                        }
                                    });
                        }
                    } else {
                        /**
                         *
                         */
                        System.arraycopy(frame, 0, imageSrc, 0, length);
                    }
                }
            }
        };
    }

    /**
     * @param mHandler
     */
    public void setmHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param image
     */
    public void setImageSrc(byte[] image) {
        this.imageSrc = image;
    }

    /**
     * @param temperatureSrc
     */
    public void setTemperatureSrc(byte[] temperatureSrc) {
        this.temperatureSrc = temperatureSrc;
    }

    /**
     * @param frameReady
     */
    public void setFrameReady(boolean frameReady) {
        isFrameReady = frameReady;
    }

    /**
     * 判断是否是红外设备，请把您的设备的PID添加进设备PID白名单
     *
     * @param devpid
     * @return
     */
    private boolean isIRpid(int devpid) {
        for (int x : pids) {
            if (x == devpid) return true;
        }
        return false;
    }

    /**
     * @param cameraHeight
     * @param cameraWidth
     */
    public void init(int cameraWidth, int cameraHeight) {
        Log.w(TAG, "init");
        // UVCCamera init
        /**
         * cameraWidth:256,cameraHeight:384,图像+温度
         * cameraWidth:256,cameraHeight:192,图像
         * cameraWidth:256,cameraHeight:192,(调用startY16ModePreview，传入Y16_MODE_TEMPERATURE)温度
         */
        ConcreateUVCBuilder concreateUVCBuilder = new ConcreateUVCBuilder();
        uvcCamera = concreateUVCBuilder
                .setUVCType(UVCType.USB_UVC)
                .setOutputWidth(cameraWidth)
                .setOutputHeight(cameraHeight)
                .build();
        // IRCMD init
        ConcreteIRCMDBuilder concreteIRCMDBuilder = new ConcreteIRCMDBuilder();
        ircmd = concreteIRCMDBuilder
                .setIrcmdType(IRCMDType.USB_IR_256_384)
                .setIdCamera(uvcCamera.getNativePtr())
                .build();
    }

    /**
     * @return
     */
    public UVCCamera getUvcCamera() {
        return uvcCamera;
    }

    /**
     * @return
     */
    public IRCMD getIrcmd() {
        return ircmd;
    }

    /**
     *
     */
    public void registerUSB() {
        if (mUSBMonitor != null) {
            mUSBMonitor.register();
        }
    }

    /**
     *
     */
    public void unregisterUSB() {
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
    }

    /**
     * @return
     */
    public List<UsbDevice> getUsbDeviceList() {
        List<DeviceFilter> deviceFilters = DeviceFilter
                .getDeviceFilters(mContext, R.xml.device_filter);
        if (mUSBMonitor == null || deviceFilters == null)
//            throw new NullPointerException("mUSBMonitor ="+mUSBMonitor+"deviceFilters=;"+deviceFilters);
            return null;
        // matching all of filter devices
        return mUSBMonitor.getDeviceList(deviceFilters);
    }

    /**
     * @param index
     */
    public void requestPermission(int index) {
        List<UsbDevice> devList = getUsbDeviceList();
        if (devList == null || devList.size() == 0) {
            return;
        }
        int count = devList.size();
        if (index >= count)
            new IllegalArgumentException("index illegal,should be < devList.size()");
        if (mUSBMonitor != null) {
            mUSBMonitor.requestPermission(getUsbDeviceList().get(index));
        }
    }

    /**
     * @param ctrlBlock
     */
    public void openUVCCamera(USBMonitor.UsbControlBlock ctrlBlock, CommonParams.DataFlowMode dataFlowMode) {
        Log.i(TAG, "openUVCCamera");
        if (ctrlBlock.getProductId() == TinyB) {
            if (syncimage != null) {
                syncimage.type = 1;
            }
        }
        if (uvcCamera == null) {
            init(cameraWidth, cameraHeight);
        }
        // 对于p2来说，复合出图需要设置帧率为25,单独出图帧率仍为25
        int DEFAULT_PREVIEW_MIN_FPS = 1;
        int DEFAULT_PREVIEW_MAX_FPS = 50;
        // uvc开启
        uvcCamera.openUVCCamera(ctrlBlock, DEFAULT_PREVIEW_MIN_FPS, DEFAULT_PREVIEW_MAX_FPS);
    }

    /**
     *
     */
    public void startPreview() {
        Log.i(TAG, "startPreview");
        uvcCamera.setOpenStatus(true);
        uvcCamera.setFrameCallback(iFrameCallback);
        // 使用ISP算法
        if (isUseIRISP) {
            initIRISP();
        } else {
            //
            uvcCamera.onStartPreview();
        }
    }

    /**
     *
     */
    public void stopPreview() {
        Log.i(TAG, "stopPreview");
        if (uvcCamera != null) {
            if (uvcCamera.getOpenStatus()) {
                uvcCamera.onStopPreview();
            }
            uvcCamera.setFrameCallback(null);
            final UVCCamera camera;
            camera = uvcCamera;
            uvcCamera = null;
            SystemClock.sleep(200);
            camera.onDestroyPreview();
        }
    }

    /**
     * 使用IRISP算法之后，需要初始化参数传递进去
     * 里面有耗时操作，需要等待执行完
     */
    private void initIRISP() {
        // 获取机芯温度,需要上传到UVC(探测器的温度不会突变，5s上传一次即可)
        ircmd.getCurrentVTemperature(curVtemp);
        // 传递机芯温度给UVC,实现数据流和命令流的分离
        uvcCamera.setCurVTemp(curVtemp[0]);
        Log.i(TAG, "ktbt_init->CurVTemp=" + curVtemp[0]);

        /**
         * 初始化参数
         * 应该把高低增益都传递进去，然后根据需要来决定使用高增益还是低增益
         */
        uvcCamera.initIRISPModule();
        // 初始化之后可以自定义环境修正参数
        uvcCamera.setEnvCorrectParams(16384, 16384, 300 * 16, 300 * 16);
        // 设置当前的增益装填，不设置则默认为高增益
        uvcCamera.setGainStatus(gainStatus);
        //
        uvcCamera.onStartPreview();
    }

    /**
     *
     */
    public void getIRISPfParamData() {
        // 是否使用保存下来的数据，方便测试查看效果和验证问题
        boolean isUseSaveData = false;
        Log.i(TAG,"getIRISPfParamData->isUseSaveData="+isUseSaveData);
        //
        new Thread(new Runnable() {
            @Override
            public void run() {
                AssetManager am = mContext.getAssets();
                InputStream is = null;
                try {
                    if (isUseSaveData) {
                        //=== 直接从文件中读取
                        is = am.open("priv_high.bin");
                        int lenth_priv = is.available();
                        priv_high = new byte[lenth_priv];
                        if (is.read(priv_high) != lenth_priv) {
                            Log.d(TAG, "read priv file fail ");
                        }
                        Log.d(TAG, "read priv file lenth " + lenth_priv);
                    } else {
                        //=== 从机芯里面读取，读取之后可以保存起来，下次直接从文件中读取
                        ircmd.readPrivData(gainMode, priv_high, priv_low);
                        // 保存数据，方便查看，可按照需要确定是否保存
                        FileUtil.saveByteFile(priv_high, "priv_high");
                        FileUtil.saveByteFile(priv_low, "priv_low");
                    }
                    for (int i = 0; i < 6; i++) {
                        Log.i(TAG, "ktbt_init->priv_data[" + i + "]=" + priv_high[i]);
                    }

                    if (isUseSaveData) {
                        //=== 直接从文件中读取
                        is = am.open("kt_high.bin");
                        int lenthKt = is.available();
                        byte kt_high_byte[] = new byte[lenthKt];
                        if (is.read(kt_high_byte) != lenthKt) {
                            Log.d(TAG, "read kt file fail ");
                        }
                        Log.d(TAG, "read kt file lenth " + lenthKt);
                        kt_high = FileUtil.toShortArray(kt_high_byte);
                    } else {
                        //=== 从机芯里面读取，读取之后可以保存起来，下次直接从文件中读取
                        ircmd.readKTData(gainMode, kt_high, kt_low);
                        // 保存数据，方便查看，可按照需要确定是否保存
                        FileUtil.saveShortFile(kt_high, "kt_high");
                        FileUtil.saveShortFile(kt_low, "kt_low");
                    }
                    for (int i = 0; i < 5; i++) {
                        Log.i(TAG, "ktbt_init->kt_data[" + i + "]=" + kt_high[i]);
                    }

                    if (isUseSaveData) {
                        //=== 直接从文件中读取
                        is = am.open("bt_high.bin");
                        int lenthBt = is.available();
                        byte bt_high_byte[] = new byte[lenthBt];
                        if (is.read(bt_high_byte) != lenthBt) {
                            Log.d(TAG, "read file fail ");
                        }
                        Log.d(TAG, "read bt file lenth " + lenthBt);
                        bt_high = FileUtil.toShortArray(bt_high_byte);
                    } else {
                        //=== 从机芯里面读取，读取之后可以保存起来，下次直接从文件中读取
                        ircmd.readBTData(gainMode, bt_high, bt_low);
                        // 保存数据，方便查看，可按照需要确定是否保存
                        FileUtil.saveShortFile(bt_high, "bt_high");
                        FileUtil.saveShortFile(bt_low, "bt_low");
                    }
                    for (int i = 0; i < 5; i++) {
                        Log.i(TAG, "ktbt_init->bt_data[" + i + "]=" + bt_high[i]);
                    }

                    if (isUseSaveData) {
                        //=== 直接从文件中读取
                        is = am.open("nuc_table_high.bin");
                        int lenthNuc = is.available();
                        byte nuc_table_high_byte[] = new byte[lenthNuc];
                        if (is.read(nuc_table_high_byte) != lenthNuc) {
                            Log.d(TAG, "read nuc_table file fail ");
                        }
                        Log.d(TAG, "read nuc_table file lenth " + lenthNuc);
                        nuc_table_high = FileUtil.toShortArray(nuc_table_high_byte);
                    } else {
                        //=== 根据不同的高低增益加载不同的等效大气透过率表
                        int[] valueGain = new int[1];
                        ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, valueGain);
                        Log.i(TAG, "TPD_PROP_GAIN_SEL=" + valueGain[0]);

                        if (valueGain[0] == 1) {
                            // 当前机芯为高增益
                            gainStatus = CommonParams.GainStatus.HIGH_GAIN;
                        } else {
                            // 当前机芯为低增益
                            gainStatus = CommonParams.GainStatus.LOW_GAIN;
                        }
                        // 从机芯里面读取，读取之后可以保存起来，下次直接从文件中读取
                        ircmd.readNucTableFromFlash(gainMode, gainStatus, nuc_table_high, nuc_table_low, isReadNuc);
                        // 保存数据，方便查看，可按照需要确定是否保存
                        FileUtil.saveShortFile(nuc_table_high, "nuc_table_high");
                        FileUtil.saveShortFile(nuc_table_low, "nuc_table_low");
                    }
                    // 获取nuc_table表数据
                    for (int i = 4000; i < 5000; i += 100) {
                        Log.i(TAG, "ktbt_init->nuc_table_high[" + i + "]=" + nuc_table_high[i]);
                    }
                    for (int i = 4000; i < 5000; i += 100) {
                        Log.i(TAG, "ktbt_init->nuc_table_low[" + i + "]=" + nuc_table_low[i]);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                } finally {
                    try {
                        if (is != null) {
                            is.close();
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                //
                if (uvcCamera != null) {
                    uvcCamera.setTempCorrectParams(priv_high, priv_low, kt_high, kt_low, bt_high, bt_low, nuc_table_high, nuc_table_low);
                }
            }
        }).start();
    }

}
