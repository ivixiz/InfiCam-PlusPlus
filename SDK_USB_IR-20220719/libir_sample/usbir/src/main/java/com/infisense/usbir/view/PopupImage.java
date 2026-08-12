package com.infisense.usbir.view;

import android.content.Context;
import android.graphics.drawable.ColorDrawable;
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
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

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
public class PopupImage implements CompoundButton.OnCheckedChangeListener, AdapterView.OnItemSelectedListener {

    private Context mContext;
    private static final String TAG = "PopupImage";
    private static final String[] TNRArray = {"0", "1", "2", "3"};
    private static final String[] SNRArray = {"0", "1", "2", "3"};
    private static final String[] DDEArray = {"0", "1", "2", "3", "4"};
    private static final String[] AGCArray = {"0", "1", "2", "3", "4", "5"};
    private PopupWindow popupWindow;
    @BindView(R.id.btnRotate)
    Button btnRotate;
    @BindView(R.id.mirror)
    Button mirror;
    @BindView(R.id.flip)
    Button flip;
    @BindView(R.id.flip_mirror)
    Button flip_mirror;
    @BindView(R.id.derotate)
    Button derotate;
    @BindView(R.id.zoomup)
    Button zoomup;
    @BindView(R.id.zoomdown)
    Button zoomdown;
    @BindView(R.id.zoomPositionUp)
    Button zoomPositionUp;
    @BindView(R.id.zoomPositionDown)
    Button zoomPositionDown;
    @BindView(R.id.none)
    Button none;
    @BindView(R.id.TNR)
    Spinner TNR;
    @BindView(R.id.SNR)
    Spinner SNR;
    @BindView(R.id.DDE)
    Spinner DDE;
    @BindView(R.id.BRIGHTNESS)
    EditText BRIGHTNESS;
    @BindView(R.id.CONTRAST)
    EditText CONTRAST;
    @BindView(R.id.setIR)
    Button setIR;
    @BindView(R.id.btnSaveConfig)
    Button btnSaveConfig;
    @BindView(R.id.MAXGAIN)
    EditText MAXGAIN;
    @BindView(R.id.BOS)
    EditText BOS;
    @BindView(R.id.MAXGAINtext)
    TextView MAXGAINtext;
    @BindView(R.id.BOStext)
    TextView BOStext;
    @BindView(R.id.setagc)
    Button setagc;
    @BindView(R.id.reload)
    Button reload;
    @BindView(R.id.ONOFF_AGC)
    ToggleButton ONOFF_AGC;
    @BindView(R.id.ONOFF_Flyer)
    ToggleButton ONOFF_Flyer;
    @BindView(R.id.AGC)
    Spinner AGC;
    private IRCMD ircmd;
    private OnRotateListener onRotateListener;
    private ArrayAdapter<String> TNRArrayAdapter, SNRArrayAdapter, DDEArrayAdapter, AGCArrayAdapter;
    private View view;
    private boolean rotate = false;

    /**
     *
     */
    public interface OnRotateListener {
        void onRotate(boolean isRotate);
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

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param context
     * @param onRotateListener
     * @param dismissListener
     */
    public PopupImage(Context context, OnRotateListener onRotateListener, PopupWindow.OnDismissListener dismissListener) {
        this.mContext = context;
        TNRArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, TNRArray);
        SNRArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, SNRArray);
        DDEArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, DDEArray);
        AGCArrayAdapter = new ArrayAdapter<String>(context, R.layout.spinner_custom, AGCArray);
        this.onRotateListener = onRotateListener;
        view = LayoutInflater.from(context).inflate(R.layout.layout_image, null);
        ButterKnife.bind(this, view);
        TNR.setAdapter(TNRArrayAdapter);
        SNR.setAdapter(SNRArrayAdapter);
        DDE.setAdapter(DDEArrayAdapter);
        AGC.setAdapter(AGCArrayAdapter);
        View.OnClickListener handler = new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                int result;
                switch (view.getId()) {
                    case R.id.btnRotate:
                        onRotateListener.onRotate(true);
                        break;
                    case R.id.derotate:
                        onRotateListener.onRotate(false);
                        break;
                    case R.id.mirror:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP, CommonParams.PropImageParamsValue.MirrorFlipType.ONLY_MIRROR);
                        break;
                    case R.id.flip:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP, CommonParams.PropImageParamsValue.MirrorFlipType.ONLY_FLIP);
                        break;
                    case R.id.flip_mirror:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP, CommonParams.PropImageParamsValue.MirrorFlipType.MIRROR_FLIP);
                        break;
                    case R.id.none:
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_MIRROR_FLIP, CommonParams.PropImageParamsValue.MirrorFlipType.NO_MIRROR_FLIP);
                        break;
                    case R.id.reload:
                        result = ircmd.loadDefaultParams(CommonParams.DefaultConfigParams.PROP_SEL_IMAGE);
                        if (result == 0) {
                            Toast.makeText(mContext, "success,power off and restart", Toast.LENGTH_SHORT).show();
                            getImageParam();
                        } else {
                            Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    case R.id.zoomdown:
                        ircmd.zoomCenterDown(CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomup:
                        ircmd.zoomCenterUp(CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomPositionDown:
                        ircmd.zoomPositionDown(100, 100, CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.zoomPositionUp:
                        ircmd.zoomPositionUp(100, 100, CommonParams.PreviewPathChannel.PREVIEW_PATH0, CommonParams.ZoomScaleStep.ZOOM_STEP2);
                        break;
                    case R.id.setagc: {
                        String MAXGAINStr = MAXGAIN.getText().toString().trim();
                        if (MAXGAINStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN,
                                    new CommonParams.PropImageParamsValue.NumberType(MAXGAINStr));
                        }
                        String BOSStr = BOS.getText().toString().trim();
                        if (BOSStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS,
                                    new CommonParams.PropImageParamsValue.NumberType(BOSStr));
                        }
                        break;
                    }
                    case R.id.setIR: {
                        String CONTRASTStr = CONTRAST.getText().toString().trim();
                        if (CONTRASTStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_CONTRAST,
                                    new CommonParams.PropImageParamsValue.NumberType(CONTRASTStr));
                        }
                        String BRIGHTNESSStr = BRIGHTNESS.getText().toString().trim();
                        if (BRIGHTNESSStr.length() != 0) {
                            ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BRIGHTNESS,
                                    new CommonParams.PropImageParamsValue.NumberType(BRIGHTNESSStr));
                        }
                        break;
                    }
                    case R.id.btnSaveConfig: {
                        // save param config
                        result = ircmd.saveSpiConfig(CommonParams.SpiConfigType.SPI_MOD_CFG_ALL);
                        if (result == 0) {
                            Toast.makeText(mContext, "success", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(mContext, "fail", Toast.LENGTH_SHORT).show();
                        }
                        break;
                    }
                }
            }
        };
        btnRotate.setOnClickListener(handler);
        derotate.setOnClickListener(handler);
        mirror.setOnClickListener(handler);
        flip.setOnClickListener(handler);
        flip_mirror.setOnClickListener(handler);
        zoomdown.setOnClickListener(handler);
        zoomup.setOnClickListener(handler);
        zoomPositionDown.setOnClickListener(handler);
        zoomPositionUp.setOnClickListener(handler);
        none.setOnClickListener(handler);
        reload.setOnClickListener(handler);
        setIR.setOnClickListener(handler);
        btnSaveConfig.setOnClickListener(handler);
        setagc.setOnClickListener(handler);
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

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        switch (buttonView.getId()) {
            case R.id.ONOFF_AGC: {
                if (isChecked) {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC,
                            CommonParams.PropImageParamsValue.StatusSwith.ON);
                } else {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC,
                            CommonParams.PropImageParamsValue.StatusSwith.OFF);
                }
                break;
            }
            case R.id.ONOFF_Flyer: {
                if (isChecked) {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_FLYER,
                            CommonParams.PropImageParamsValue.StatusSwith.ON);
                } else {
                    ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_FLYER,
                            CommonParams.PropImageParamsValue.StatusSwith.OFF);
                }
                break;
            }
        }
    }

    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        switch (parent.getId()) {
            case R.id.TNR:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR,
                                CommonParams.PropImageParamsValue.TNRType.TNR_3);
                        break;
                    }
                }
                break;
            case R.id.SNR:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR,
                                CommonParams.PropImageParamsValue.SNRType.SNR_3);
                        break;
                    }
                }
                break;
            case R.id.DDE:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_3);
                        break;
                    }
                    case 4: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE,
                                CommonParams.PropImageParamsValue.DDEType.DDE_4);
                        break;
                    }
                }
                break;
            case R.id.AGC:
                switch (position) {
                    case 0: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_0);
                        break;
                    }
                    case 1: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_1);
                        break;
                    }
                    case 2: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_2);
                        break;
                    }
                    case 3: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_3);
                        break;
                    }
                    case 4: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_4);
                        break;
                    }
                    case 5: {
                        ircmd.setPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC,
                                CommonParams.PropImageParamsValue.AGCType.AGC_5);
                        break;
                    }
                }
                // AGC不同档位都对应了一对MAXGAIN和BOS,也就是说AGC切换档位都应该重新读一下MAXGAIN和BOS,不同档位重新设置后值是不同的
                int[] mode = new int[1];
                ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN, mode);
                MAXGAIN.setText(String.valueOf(mode[0]));
                Log.i(TAG, "AGC = " + position + " MAXGAIN = " + mode[0]);
                ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS, mode);
                BOS.setText(String.valueOf(mode[0]));
                Log.i(TAG, "AGC = " + position + " BOS = " + mode[0]);
                break;
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
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_TNR, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        TNR.setOnItemSelectedListener(null);
        TNR.setSelection(mode[0], true);
        TNR.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_SNR, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        SNR.setOnItemSelectedListener(null);
        SNR.setSelection(mode[0], true);
        SNR.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_DDE, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        DDE.setOnItemSelectedListener(null);
        DDE.setSelection(mode[0], true);
        DDE.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_MODE_AGC, mode);
        // 上电之后立即读取会出现错误值的情况，需要等待出图稳定之后再读取
        AGC.setOnItemSelectedListener(null);
        AGC.setSelection(mode[0], true);
        AGC.setOnItemSelectedListener(this);
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_MAX_GAIN, mode);
        MAXGAIN.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BOS, mode);
        BOS.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_CONTRAST, mode);
        CONTRAST.setText(mode[0] + "");
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_LEVEL_BRIGHTNESS, mode);
        BRIGHTNESS.setText(mode[0] + "");
        //
        ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_ONOFF_AGC, mode);
        ONOFF_AGC.setOnCheckedChangeListener(null);
        ONOFF_AGC.setChecked(mode[0] == 1);
        ONOFF_AGC.setOnCheckedChangeListener(this);
        //
        Log.i(TAG, "IMAGE_PROP_SEL_FLYER-result:" + ircmd.getPropImageParams(CommonParams.PropImageParams.IMAGE_PROP_SEL_FLYER, mode));
        ONOFF_Flyer.setOnCheckedChangeListener(null);
        ONOFF_Flyer.setChecked(mode[0] == 1);
        ONOFF_Flyer.setOnCheckedChangeListener(this);
    }

}
