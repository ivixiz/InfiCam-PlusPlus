package com.infisense.usbir.view;

import android.Manifest;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.sdkisp.LibIRTemp;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.CommonUtils;
import com.infisense.usbir.R;
import com.infisense.usbir.utils.FileUtil;

import java.io.File;

import butterknife.BindView;
import butterknife.ButterKnife;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2021.12.9 13:45
 * @UpdateUser:
 * @UpdateDate:     2021.12.9 13:45
 * @UpdateRemark:
 */
public class PopupOthers implements View.OnClickListener, CompoundButton.OnCheckedChangeListener,
        AdapterView.OnItemSelectedListener {

    private static final String TAG = "PopupOthers";
    private static final String[] tpdtype = {"TPD_PROP_DISTANCE", "TPD_PROP_TU", "TPD_PROP_TA", "TPD_PROP_EMS", "TPD_PROP_TAU", "TPD_PROP_GAIN_SEL", "TPD_PROP_P0", "TPD_PROP_P1", "TPD_PROP_P2"};
    private static final String[] saveConfigArray = {"0", "SPI_MOD_CFG_ALL", "SPI_MOD_CFG_DEAD_PIX", "SPI_MOD_CFG_PROPERTY_PAGE"};
    private static final String[] restoreConfigArray = {"0", "DEF_CFG_ALL", "DEF_CFG_TECLESS_K", "DEF_CFG_TPD", "DEF_CFG_PROP_PAGE", "DEF_CFG_USER_CFG", "DEF_CFG_DEAD_PIXEL"};
    private IRCMD ircmd;
    private PopupWindow popupWindow;
    public Context mContext;
    // 当前的增益状态
    private CommonParams.GainStatus gainStatus = CommonParams.GainStatus.HIGH_GAIN;
    // 模组支持的高低增益模式
    private CommonParams.GainMode gainMode = CommonParams.GainMode.GAIN_MODE_HIGH_LOW;
    private byte[] tau_data_H;
    private byte[] tau_data_L;
    private long tempinfo = 0;
    private short[] nuc_table_high = new short[8192];
    private short[] nuc_table_low = new short[8192];
    private boolean isReadNuc = true; // 是否读取nuc信息，首次读取nuc信息，后续存储到文件中下次直接传入不用重新读取
    //
    private TextView tvDTEMP;
    private EditText etFPS;
    //
    @BindView(R.id.automode)
    ToggleButton automode;
    @BindView(R.id.min)
    EditText min;
    @BindView(R.id.mintext)
    TextView mintext;
    @BindView(R.id.max)
    EditText max;
    @BindView(R.id.maxtext)
    TextView maxtext;
    @BindView(R.id.ooc)
    EditText ooc;
    @BindView(R.id.ooctext)
    TextView ooctext;
    @BindView(R.id.b)
    EditText b;
    @BindView(R.id.btext)
    TextView btext;
    //
    @BindView(R.id.Param_Sel)
    Spinner Param_Sel;
    @BindView(R.id.data)
    EditText data;
    private int[] param = new int[9];
    //
    @BindView(R.id.saveConfig)
    Spinner saveConfig;
    @BindView(R.id.restoreConfig)
    Spinner restoreConfig;
    private ArrayAdapter<String> saveConfigAdapter, restoreConfigAdapter;
    // progressDialog
    private ProgressDialog progressDialog;
    /**
     * 机芯数据存储目录
     */
    private String fileSaveDir; // 文件的存储目录

    private final int MESSAGE_CODE_READ_NUC_SUCCESS = 1000;
    private final int MESSAGE_CODE_RESTORE_CONFIG_SUCCESS = 1001;
    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == MESSAGE_CODE_READ_NUC_SUCCESS) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                Toast.makeText(mContext, "read nuc success", Toast.LENGTH_SHORT).show();
            } else if (msg.what == MESSAGE_CODE_RESTORE_CONFIG_SUCCESS) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                int result = (int) msg.obj;
                if (result == 0) {
                    Toast.makeText(mContext, mContext.getResources().getString(R.string.restore_restart), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /**
     * @param context
     * @param dismissListener
     */
    public PopupOthers(Context context, PopupWindow.OnDismissListener dismissListener) {
        this.mContext = context;
        View view = LayoutInflater.from(context).inflate(R.layout.layout_others, null);
        ButterKnife.bind(this, view);
        //
        popupWindow = new PopupWindow(view);
        popupWindow.setWidth(ViewGroup.LayoutParams.MATCH_PARENT);
        popupWindow.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popupWindow.setFocusable(true);
        popupWindow.setOutsideTouchable(false);
        popupWindow.setOnDismissListener(dismissListener);
        popupWindow.setBackgroundDrawable(new ColorDrawable(0x00000000)); // 解决 7.0 手机，点击外部不消失
        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        //创建布局管理
        LinearLayoutManager layoutManager = new LinearLayoutManager(context);
        layoutManager.setOrientation(LinearLayoutManager.HORIZONTAL);
        //===
        view.findViewById(R.id.btnTempCorrection1).setOnClickListener(this);
        view.findViewById(R.id.btnTempCorrection2).setOnClickListener(this);
        view.findViewById(R.id.btnTempCorrection3).setOnClickListener(this);
        view.findViewById(R.id.btnShutSubmit).setOnClickListener(this);
        view.findViewById(R.id.btnTpdSubmit).setOnClickListener(this);
        tvDTEMP = view.findViewById(R.id.tvDTEMP);
        etFPS = view.findViewById(R.id.etFPS);
        view.findViewById(R.id.btnFPS).setOnClickListener(this);
        //
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, tpdtype);
        Param_Sel.setAdapter(adapter);
        Param_Sel.setOnItemSelectedListener(this);
        //
        saveConfigAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, saveConfigArray);
        saveConfig.setAdapter(saveConfigAdapter);
        saveConfig.setOnItemSelectedListener(this);
        restoreConfigAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, restoreConfigArray);
        restoreConfig.setAdapter(restoreConfigAdapter);
        restoreConfig.setOnItemSelectedListener(this);
    }

    /**
     * @param parent
     */
    public void showAsDropDown(View parent) {
        popupWindow.showAsDropDown(parent);
        if (ircmd != null) {
            getImageParam();
        }
        //
        int[] currentVTemperature = new int[1];
        ircmd.getCurrentVTemperature(currentVTemperature);
        int[] shutterVTemperature = new int[1];
        ircmd.getShutterVTemperature(shutterVTemperature);
        tvDTEMP.setText(mContext.getResources().getString(R.string.currentVTemp) + currentVTemperature[0] + "  " +
                mContext.getResources().getString(R.string.shutterVTemp) + shutterVTemperature[0]);
        etFPS.setSelection(etFPS.getText().length());
        //
        Param_Sel.setSelection(0);
        saveConfig.setSelection(0);
        restoreConfig.setSelection(0);
    }

    /**
     * @param ircmd
     */
    public void setIrcmd(IRCMD ircmd) {
        this.ircmd = ircmd;
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btnTempCorrection1: {
                // Temperature correction step 1
                // 环境变量校准参数导入测试代码，该方法比较耗时，需要在子线程中执行，且只需要执行一次
                // 和releaseTemperatureCorrection方法成对出现
                progressDialog = ProgressDialog.show(mContext, "", "reading nuc", true);
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        // 根据不同的高低增益加载不同的等效大气透过率表
                        int[] value = new int[1];
                        ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
                        Log.d(TAG, "TPD_PROP_GAIN_SEL=" + value[0]);
                        if (value[0] == 1) {
                            // 当前机芯为高增益
                            gainStatus = CommonParams.GainStatus.HIGH_GAIN;
                            // 等效大气透过率表
                        } else {
                            // 当前机芯为低增益
                            gainStatus = CommonParams.GainStatus.LOW_GAIN;
                        }
                        /**
                         * 这里需要根据自己的实际镜头尺寸来加载对应的文件
                         * 如：91:9.1mm镜头
                         *    135:13.5mm镜头
                         */
                        tau_data_H = CommonUtils.getTauData(mContext, "tau/P2-3.2mm-H-V16.bin");
                        tau_data_L = CommonUtils.getTauData(mContext, "tau/P2-3.2mm-L-V16.bin");
                        Log.i(TAG, "tau_data_H[" + 100 + "]=" + tau_data_H[100] + " tau_data_L[" + 100 + "]=" + tau_data_L[100]);

                        /**
                         * readNucTableFromFlash为耗时操作，影响用户体验
                         * 这里的优化逻辑为：以SN码为唯一标识，首次读取nuc table信息后存储起来，
                         * 下次进来之后判断是否存在nuc table文件，存在则直接使用；否则则重新读取并存储
                         */
                        byte[] SN = new byte[16];
                        ircmd.getDeviceInfo(CommonParams.DeviceInfoType.DEV_INFO_GET_SN, SN); //ok
                        String snCodeInfo = (new String(SN)).substring(0, 12);
                        fileSaveDir = mContext.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS).getAbsolutePath();
                        String deviceSNUnCodePath = fileSaveDir + File.separator + snCodeInfo;
                        String nucHighPath = deviceSNUnCodePath + "_nuc_table_high.bin";
                        String nucLowPath = deviceSNUnCodePath + "_nuc_table_low.bin";
                        if (!snCodeInfo.isEmpty() && FileUtil.isFileExists(mContext, nucHighPath) && FileUtil.isFileExists(mContext, nucLowPath) &&
                                ContextCompat.checkSelfPermission(mContext, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                            // 已经存在文件，使用存放的
                            Log.i(TAG, "已经存在文件:" + nucHighPath + "\n" + nucLowPath);
                            byte[] nuc_table_high_byte = FileUtil.readFile2BytesByStream(mContext, new File(nucHighPath));
                            nuc_table_high = FileUtil.toShortArray(nuc_table_high_byte);
                            //
                            byte[] nuc_table_low_byte = FileUtil.readFile2BytesByStream(mContext, new File(nucLowPath));
                            nuc_table_low = FileUtil.toShortArray(nuc_table_low_byte);
                            isReadNuc = false;
                            if (ircmd != null) {
                                tempinfo = ircmd.readNucTableFromFlash(gainMode, gainStatus, nuc_table_high, nuc_table_low, isReadNuc);
                            }
                        } else {
                            // 获取机芯中的测温修正系数
                            Log.i(TAG, "获取机芯中数据");
                            isReadNuc = true;
                            if (ircmd != null) {
                                tempinfo = ircmd.readNucTableFromFlash(gainMode, gainStatus, nuc_table_high, nuc_table_low, isReadNuc);
                            }
                            if (!snCodeInfo.isEmpty() && ContextCompat.checkSelfPermission(mContext, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                                // 保存数据，方便查看，可按照需要确定是否保存
                                FileUtil.saveShortFile(fileSaveDir, nuc_table_high, snCodeInfo + "_nuc_table_high");
                                FileUtil.saveShortFile(fileSaveDir, nuc_table_low, snCodeInfo + "_nuc_table_low");
                            }
                        }
                        // 获取nuc_table表数据
                        Log.i(TAG, "nuc_table_high[" + 0 + "]=" + nuc_table_high[0] + " nuc_table_low[" + 0 + "]=" + nuc_table_low[0]);
                        //
                        Message message = new Message();
                        message.what = MESSAGE_CODE_READ_NUC_SUCCESS;
                        mHandler.sendMessage(message);
                    }
                }).start();
                break;
            }
            case R.id.btnTempCorrection2: {
                // Temperature correction step 2
                // 环境变量修正，可以执行多次，对多个温度进行修正
                // 环境变量校准之前的温度，单位为摄氏度
                float oldTemp = 400F;
                float ems = 1F;
                float ta = 25F;
                float tu = 25F;
                float distance = 1F;
                float hum = 0.45F;
                /**
                 * temperature correction with origin method
                 */
                float newtemp = ircmd.temperatureCorrection(oldTemp, tau_data_H, tau_data_L, ems, ta, tu, 1, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + oldTemp + " ems = " + ems + " ta = " + ta + " distance = " + 1 + " hum = " + hum + " newtemp = " + newtemp);

                float newtemp1 = ircmd.temperatureCorrection(oldTemp, tau_data_H, tau_data_L, ems, ta, tu, 3, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + oldTemp + " ems = " + ems + " ta = " + ta + " distance = " + 3 + " hum = " + hum + " newtemp = " + newtemp1);

                float newtemp2 = ircmd.temperatureCorrection(oldTemp, tau_data_H, tau_data_L, ems, ta, tu, 5, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + oldTemp + " ems = " + ems + " ta = " + ta + " distance = " + 5 + " hum = " + hum + " newtemp = " + newtemp2);


                float newtemp7 = ircmd.temperatureCorrection(71.6F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 71.6F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp7);

                float newtemp8 = ircmd.temperatureCorrection(96.725F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 96.725F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp8);

                float newtemp3 = ircmd.temperatureCorrection(239.1F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 239.1F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp3);

                float newtemp4 = ircmd.temperatureCorrection(327.913F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 327.913F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp4);

                float newtemp5 = ircmd.temperatureCorrection(374.85F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 374.85F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp5);

                float newtemp6 = ircmd.temperatureCorrection(416.913F, tau_data_H, tau_data_L, ems, ta, tu, distance, hum, tempinfo, gainStatus);
                Log.i(TAG, "temp correct, oldTemp = " + 416.913F + " ems = " + ems + " ta = " + ta + " distance = " + distance + " hum = " + hum + " newtemp = " + newtemp6);


                // 读取等效大气透过率(temperatureCorrection接口中已经包含了读取等效大气透过率，这里单独列出来方便单独使用)
                int[] tauvalue = new int[1];
                LibIRTemp.readTau(gainStatus, tau_data_H, tau_data_L, hum, oldTemp, distance, tauvalue);
                Log.d(TAG, "readTau, oldTemp = " + oldTemp + " tau = " + tauvalue[0]);
                break;
            }
            case R.id.btnTempCorrection3: {
                // Temperature correction step 3
                // 释放资源，和readNucTableFromFlash方法成对出现，且只需要执行一次
                Log.i(TAG, "releaseTemperatureCorrection-start");
                if (tempinfo != 0) {
                    ircmd.releaseTemperatureCorrection(tempinfo, isReadNuc);
                }
                Log.i(TAG, "releaseTemperatureCorrection-end");
                break;
            }
            case R.id.btnFPS: {
                // 输出帧频调节
                int fpsNum = Integer.parseInt(etFPS.getText().toString().trim());
                if (fpsNum < 1 || fpsNum > 30) {
                    Toast.makeText(mContext, "输出帧频范围1~30", Toast.LENGTH_SHORT).show();
                    return;
                }
                ircmd.setPreviewFPS((char) fpsNum);
                break;
            }
            case R.id.btnShutSubmit: {
                // 自动快门参数设置
                String minValue = min.getText().toString().trim();
                if (minValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MIN_INTERVAL,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(minValue));
                }
                String maxValue = max.getText().toString().trim();
                if (maxValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MAX_INTERVAL,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(maxValue));
                }
                String oocValue = ooc.getText().toString().trim();
                if (oocValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_OOC,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(oocValue));
                }
                String bValue = b.getText().toString().trim();
                if (bValue.length() != 0) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_B,
                            new CommonParams.PropAutoShutterParameterValue.NumberType(bValue));
                }
                break;
            }
            case R.id.btnTpdSubmit: {
                //
                String value = data.getText().toString().trim();
                if (value.length() != 0) {
                    param[Param_Sel.getSelectedItemPosition()] = (char) Integer.parseInt(value);
                    switch (Param_Sel.getSelectedItemPosition()) {
                        case 0: {
                            /// Distance property. unit:cnt(128cnt=1m), range:0-25600(0-200m)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_DISTANCE,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 1: {
                            /// Reflection temperature property. unit:K, range:230-500(high gain), 230-900(low gain)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TU,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 2: {
                            /// Atmospheric temperature property. unit:K, range:230-500(high gain), 230-900(low gain)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TA,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 3: {
                            /// Emissivity property. unit:1/128, range:1-128(0.01-1)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 4: {
                            /// Atmospheric transmittance property. unit:1/128, range:1-128(0.01-1)
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TAU,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 5: {
                            /// Gain select. 0:low gain, 1:high gain
                            if (value.equals("0") || value.equals("1")) {
                                if (Integer.parseInt(value) == 0) {
                                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_LOW);
                                } else {
                                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_HIGH);
                                }
                            } else {
                                Toast.makeText(mContext, "Gain value is 0 or 1", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        }
                        case 6: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P0,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 7: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P1,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        case 8: {
                            //
                            ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P2,
                                    new CommonParams.PropTPDParamsValue.NumberType(value));
                            break;
                        }
                        default:
                            break;
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.automode: {
                // 自动快门
                if (isChecked) {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH,
                            CommonParams.PropAutoShutterParameterValue.StatusSwith.ON);
                } else {
                    ircmd.setPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH,
                            CommonParams.PropAutoShutterParameterValue.StatusSwith.OFF);
                }
                break;
            }
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.Param_Sel: {
                data.setText(param[position] + "");
                data.setSelection((param[position] + "").length());
                break;
            }
            case R.id.saveConfig: {
                if (position != 0) {
                    int result = 0;
                    switch (position) {
                        case 1:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_ALL);
                            break;
                        case 2:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_DEAD_PIX);
                            break;
                        case 3:
                            result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_PROPERTY_PAGE);
                            break;
                    }
                    if (result == 0) {
                        Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                    }
                }
                break;
            }
            case R.id.restoreConfig: {
                if (position != 0) {
                    progressDialog = ProgressDialog.show(mContext, "", "restoring", true);
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            int result = 0;
                            switch (position) {
                                case 1:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_ALL);
                                    break;
                                case 2:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TECLESS_K);
                                    break;
                                case 3:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD);
                                    break;
                                case 4:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_PROP_PAGE);
                                    break;
                                case 5:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_USER_CFG);
                                    break;
                                case 6:
                                    result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_DEAD_PIXEL);
                                    break;
                            }
                            //
                            Message message = new Message();
                            message.what = MESSAGE_CODE_RESTORE_CONFIG_SUCCESS;
                            message.obj = result;
                            mHandler.sendMessage(message);
                        }
                    }).start();
                }
                break;
            }
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    /**
     *
     */
    private void getImageParam() {
        int[] mode = new int[1];
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_SWITCH, mode);
        automode.setOnCheckedChangeListener(null);
        automode.setChecked(mode[0] == 1);
        automode.setOnCheckedChangeListener(this);
        //
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MIN_INTERVAL, mode);
        min.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_MAX_INTERVAL, mode);
        max.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_OOC, mode);
        ooc.setText(mode[0] + "");
        ircmd.getPropAutoShutterParameter(CommonParams.PropAutoShutterParameter.SHUTTER_PROP_TEMP_THRESHOLD_B, mode);
        b.setText(mode[0] + "");
        //
        int i = 0;
        int[] value = new int[1];
        for (; i < 9; i++) {
            switch (i) {
                case 0: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_DISTANCE, value);
                    break;
                }
                case 1: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TU, value);
                    break;
                }
                case 2: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TA, value);
                    break;
                }
                case 3: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_EMS, value);
                    break;
                }
                case 4: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_TAU, value);
                    break;
                }
                case 5: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
                    break;
                }
                case 6: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P0, value);
                    break;
                }
                case 7: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P1, value);
                    break;
                }
                case 8: {
                    ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_P2, value);
                    break;
                }
                default:
                    break;
            }
            param[i] = value[0];
        }
        //
    }


}
