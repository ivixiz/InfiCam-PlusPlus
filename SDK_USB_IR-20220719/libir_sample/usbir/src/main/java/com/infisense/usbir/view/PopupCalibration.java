package com.infisense.usbir.view;

import android.app.ProgressDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.infisense.iruvc.ircmd.IRCMD;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.usbir.R;

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
public class PopupCalibration implements CompoundButton.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {

    private Context mContext;
    private static final String TAG = "PopupCalibration";
    private PopupWindow popupWindow;
    private Button singlepointsumit;
    private Button doiblepointsumit;
    private Button endpointsumit;
    private Button cancel;
    private Button bengin;
    private Button start;
    @BindView(R.id.savecfg)
    Button savecfg;
    @BindView(R.id.restorecfg)
    Button restorecfg;
    private Button btnPointLineRectTemp;
    private Button btnFrameMaxMinTemp;
    private EditText x, y;
    // 1/2/4, defautl:1.
    private static final String[] spnRecoverArray = {"1", "2", "4"};
    private ArrayAdapter<String> spnRecoverAdapter;
    @BindView(R.id.spnRecover)
    Spinner spnRecover;
    private Button add_dp;
    private Button rm_dp;
    @BindView(R.id.etSinglePointTemp)
    EditText etSinglePointTemp;
    @BindView(R.id.etLowPointTemp)
    EditText etLowPointTemp;
    @BindView(R.id.etHighPointTemp)
    EditText etHighPointTemp;
    private Switch switchGain;
    private IRCMD ircmd;
    private View view;
    // progressDialog
    private ProgressDialog progressDialog;
    private boolean rotate = false;

    private final int MESSAGE_CODE_RESTORE_FACTORY = 1000;
    private final int MESSAGE_CODE_TPD_KTBT_RECAL_P2 = 1001;
    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == MESSAGE_CODE_RESTORE_FACTORY) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                int result = (int) msg.obj;
                if (result == 0) {
                    Toast.makeText(mContext, mContext.getResources().getString(R.string.restore_restart), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                }
            } else if (msg.what == MESSAGE_CODE_TPD_KTBT_RECAL_P2) {
                if (progressDialog != null && progressDialog.isShowing()) {
                    progressDialog.dismiss();
                }
                int result = (int) msg.obj;
                if (result == 0) {
                    endpointsumit.setVisibility(View.INVISIBLE);
                    doiblepointsumit.setVisibility(View.VISIBLE);
                    Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                }
            }
        }
    };

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param context
     * @param dismissListener
     */
    public PopupCalibration(Context context, PopupWindow.OnDismissListener dismissListener) {
        this.mContext = context;
        view = LayoutInflater.from(context).inflate(R.layout.layout_calibration, null);
        ButterKnife.bind(this, view);
        singlepointsumit = view.findViewById(R.id.singlepointsumit);
        doiblepointsumit = view.findViewById(R.id.doiblepointsumit);
        cancel = view.findViewById(R.id.cancel);
        endpointsumit = view.findViewById(R.id.endpointsumit);
        bengin = view.findViewById(R.id.bengin);
        start = view.findViewById(R.id.start);
        x = view.findViewById(R.id.x);
        y = view.findViewById(R.id.y);
        add_dp = view.findViewById(R.id.add_dp);
        rm_dp = view.findViewById(R.id.rm_dp);
        btnPointLineRectTemp = view.findViewById(R.id.btnPointLineRectTemp);
        btnFrameMaxMinTemp = view.findViewById(R.id.btnFrameMaxMinTemp);
        switchGain = view.findViewById(R.id.switchGain);
        //
        spnRecoverAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, spnRecoverArray);
        spnRecover.setAdapter(spnRecoverAdapter);
        //
        View.OnClickListener handler = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int result;
                switch (view.getId()) {
                    case R.id.singlepointsumit: {
                        // 单点标定-对准黑体-设置温度
                        String singlePointTemp = etSinglePointTemp.getText().toString().trim();
                        if (singlePointTemp.length() != 0) {
                            // 标定前需要重置测温参数,否则温度标定不准
                            if (ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD) == 0) {
                                //
                                ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD);
                                //
                                result = ircmd.setTPDKtBtRecalPoint(CommonParams.TPDKtBtRecalPointType.RECAL_1_POINT,
                                        Integer.parseInt(singlePointTemp));
                                if (result == 0) {
                                    Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                            }
                        }
                        break;
                    }
                    case R.id.doiblepointsumit: {
                        // 2点标定-低温
                        String lowPointTemp = etLowPointTemp.getText().toString().trim();
                        if (lowPointTemp.length() != 0) {
                            // 标定前需要重置测温参数,否则温度标定不准
                            if (ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD) == 0) {
                                //
                                result = ircmd.setTPDKtBtRecalPoint(CommonParams.TPDKtBtRecalPointType.RECAL_2_POINT_FIRST,
                                        Integer.parseInt(lowPointTemp));
                                if (result == 0) {
                                    doiblepointsumit.setVisibility(View.INVISIBLE);
                                    endpointsumit.setVisibility(View.VISIBLE);
                                    Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                                } else {
                                    Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                                }
                            } else {
                                Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                            }
                        }
                        break;
                    }
                    case R.id.endpointsumit: {
                        // 2点标定-高温-提交完低温之后才能提交高温
                        String highPointTemp = etHighPointTemp.getText().toString().trim();
                        if (highPointTemp.length() != 0) {
                            progressDialog = ProgressDialog.show(mContext, "", "restoring", true);
                            new Thread(new Runnable() {
                                @Override
                                public void run() {
                                    // 标定前需要重置测温参数,否则温度标定不准
                                    if (ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD) == 0) {
                                        //
                                        int result = ircmd.setTPDKtBtRecalPoint(CommonParams.TPDKtBtRecalPointType.RECAL_2_POINT_END,
                                                Integer.parseInt(highPointTemp));
                                        //
                                        Message message = new Message();
                                        message.what = MESSAGE_CODE_TPD_KTBT_RECAL_P2;
                                        message.obj = result;
                                        mHandler.sendMessage(message);
                                    } else {
                                        //
                                        Message message = new Message();
                                        message.what = MESSAGE_CODE_TPD_KTBT_RECAL_P2;
                                        message.obj = -1;
                                        mHandler.sendMessage(message);
                                    }
                                }
                            }).start();
                        }
                        break;
                    }
                    case R.id.cancel: {
                        // 取消标定
                        ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_TPD);
                        Toast.makeText(mContext, mContext.getResources().getString(R.string.restore_restart), Toast.LENGTH_SHORT).show();
                        etSinglePointTemp.setText("");
                        etLowPointTemp.setText("");
                        etHighPointTemp.setText("");
                        endpointsumit.setVisibility(View.INVISIBLE);
                        doiblepointsumit.setVisibility(View.VISIBLE);
                        break;
                    }
                    case R.id.bengin: {
                        // 锅盖标定-准备
                        result = ircmd.rmCoverStsSwitch(CommonParams.RMCoverStsSwitchStatus.RMCOVER_DIS);
                        if (result == 0) {
                            Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    }
                    case R.id.start: {
                        // 锅盖标定-开始
                        // 1/2/4, defautl:1.
                        switch (spnRecover.getSelectedItemPosition()) {
                            case 0: {
                                ircmd.rmCoverAutoCalc(CommonParams.RMCoverAutoCalcType.GAIN_1);
                                break;
                            }
                            case 1: {
                                ircmd.rmCoverAutoCalc(CommonParams.RMCoverAutoCalcType.GAIN_2);
                                break;
                            }
                            case 2: {
                                ircmd.rmCoverAutoCalc(CommonParams.RMCoverAutoCalcType.GAIN_4);
                                break;
                            }
                            default:
                                break;
                        }
                        ircmd.rmCoverStsSwitch(CommonParams.RMCoverStsSwitchStatus.RMCOVER_EN);
                        break;
                    }
                    case R.id.add_dp: {
                        // 盲元标定Dead pixel correction-在盲元表中增加
                        if (x.getText().toString().length() != 0 && y.getText().toString().length() != 0)
                            ircmd.addDeadPixelPoint(Integer.parseInt(x.getText().toString()), Integer.parseInt(y.getText().toString()));
                        int[] temperatureValue = new int[1];
                        ircmd.getPointTemperatureInfo(Integer.parseInt(x.getText().toString()), Integer.parseInt(y.getText().toString()), temperatureValue);
                        Log.i(TAG, " tempture=" + temperatureValue[0]);
                        break;
                    }
                    case R.id.rm_dp: {
                        // 盲元标定Dead pixel correction-在盲元表中去除
                        if (x.getText().toString().length() != 0 && y.getText().toString().length() != 0)
                            ircmd.removeDeadPixelPoint(Integer.parseInt(x.getText().toString()), Integer.parseInt(y.getText().toString()));
                        break;
                    }
                    case R.id.savecfg: {
                        // 盲元标定Dead pixel correction-在盲元表中增加/去除都需要调用才生效
                        ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_ALL);
                        break;
                    }
                    case R.id.restorecfg: {
                        // 恢复出厂标定
                        progressDialog = ProgressDialog.show(mContext, "", "restoring", true);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                int result = ircmd.restoreDefaultConfig(CommonParams.DefaultConfigType.DEF_CFG_ALL);
                                //
                                Message message = new Message();
                                message.what = MESSAGE_CODE_RESTORE_FACTORY;
                                message.obj = result;
                                mHandler.sendMessage(message);
                            }
                        }).start();
                        break;
                    }
                    case R.id.btnPointLineRectTemp: {
                        // 点线框测温
                        // 设置为中心点
                        int[] temperatureValue = new int[1];
                        ircmd.getPointTemperatureInfo(128, 96, temperatureValue);
                        Log.i(TAG, "point:" + temperatureValue[0] + "\nrotate = " + rotate);
                        //
                        int[] lineTemperatureValue = new int[7];
                        ircmd.getLineTemperatureInfo(10, 10, 30, 30, lineTemperatureValue);
                        Log.i(TAG, "line:ave_temp=" + lineTemperatureValue[0] +
                                "\nmax_temp=" + lineTemperatureValue[1] + " min_temp=" + lineTemperatureValue[2] +
                                "\nmax_temp_point.x=" + lineTemperatureValue[3] + " max_temp_point.y=" + lineTemperatureValue[4] +
                                "\nmin_temp_point.x=" + lineTemperatureValue[5] + " min_temp_point.y=" + lineTemperatureValue[6] +
                                "\nrotate = " + rotate);
                        //
                        int[] rectTemperatureValue = new int[7];
                        ircmd.getRectTemperatureInfo(10, 10, 30, 30, rectTemperatureValue);
                        Log.i(TAG, "rect:ave_temp=" + rectTemperatureValue[0] +
                                "\nmax_temp=" + rectTemperatureValue[1] + " min_temp=" + rectTemperatureValue[2] +
                                "\nmax_temp_point.x=" + rectTemperatureValue[3] + " max_temp_point.y=" + rectTemperatureValue[4] +
                                "\nmin_temp_point.x=" + rectTemperatureValue[5] + " min_temp_point.y=" + rectTemperatureValue[6] +
                                "\nrotate = " + rotate);
                        //
                        Toast.makeText(mContext, "point temp:" + temperatureValue[0] +
                                        "\nline:ave_temp=" + lineTemperatureValue[0] + " max_temp=" + lineTemperatureValue[1] + " min_temp=" + lineTemperatureValue[2] +
                                        "\nrect:ave_temp=" + rectTemperatureValue[0] + " max_temp=" + rectTemperatureValue[1] + " min_temp=" + rectTemperatureValue[2],
                                Toast.LENGTH_LONG).show();
                        break;
                    }
                    case R.id.btnFrameMaxMinTemp: {
                        // 帧最高温和最低温
                        //
                        int[] temperatureMinValue = new int[1];
                        ircmd.getCurrentFrameMaxTemperature(temperatureMinValue);
                        Log.i(TAG, "max_temp:" + temperatureMinValue[0]);
                        //
                        int[] temperatureMaxValue = new int[1];
                        ircmd.getCurrentFrameMinTemperature(temperatureMaxValue);
                        Log.i(TAG, "min_temp:" + temperatureMaxValue[0]);
                        //
                        int[] maxMinTemperatureValue = new int[6];
                        ircmd.getCurrentFrameMaxAndMinTemperature(maxMinTemperatureValue);
                        Log.i(TAG, "max_min_temp:max_temp=" + maxMinTemperatureValue[0] +
                                "\nmin_temp=" + maxMinTemperatureValue[1] +
                                "\nmax_temp_point.x=" + maxMinTemperatureValue[2] + " max_temp_point.y=" + maxMinTemperatureValue[3] +
                                "\nmin_temp_point.x=" + maxMinTemperatureValue[4] + " min_temp_point.y=" + maxMinTemperatureValue[5]);
                        //
                        Toast.makeText(mContext, "max_temp:" + temperatureMinValue[0] + " min_temp:" + temperatureMaxValue[0] +
                                        "\nmax_min_temp:max_temp=" + maxMinTemperatureValue[0] + " min_temp=" + maxMinTemperatureValue[1],
                                Toast.LENGTH_LONG).show();
                        break;
                    }
                }
            }
        };
        singlepointsumit.setOnClickListener(handler);
        doiblepointsumit.setOnClickListener(handler);
        endpointsumit.setOnClickListener(handler);
        cancel.setOnClickListener(handler);
        bengin.setOnClickListener(handler);
        start.setOnClickListener(handler);
        add_dp.setOnClickListener(handler);
        rm_dp.setOnClickListener(handler);
        savecfg.setOnClickListener(handler);
        restorecfg.setOnClickListener(handler);
        btnPointLineRectTemp.setOnClickListener(handler);
        btnFrameMaxMinTemp.setOnClickListener(handler);
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
    }

    /**
     * @param parent
     */
    public void showAsDropDown(View parent) {
        popupWindow.showAsDropDown(parent);
        getImageParam();
    }

    /**
     * @param ircmd
     */
    public void setIrcmd(IRCMD ircmd) {
        this.ircmd = ircmd;
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.switchGain: {
                // gain change
                if (isChecked) {
                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_HIGH);
                } else {
                    ircmd.setPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL,
                            CommonParams.PropTPDParamsValue.GAINSELStatus.GAIN_SEL_LOW);
                }
                break;
            }
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.spnRecover:
                break;
        }
    }

    @Override
    public void onNothingSelected(AdapterView<?> parent) {

    }

    /**
     *
     */
    private void getImageParam(){
        // gain status
        int[] value = new int[1];
        ircmd.getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
        Log.d(TAG, "TPD_PROP_GAIN_SEL=" + value[0]);
        switchGain.setChecked(value[0] == 1);
        switchGain.setOnCheckedChangeListener(this);
        //
        spnRecover.setSelection(0, true);
        spnRecover.setOnItemSelectedListener(this);
    }

}