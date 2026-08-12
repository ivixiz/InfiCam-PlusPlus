package com.infisense.usbir.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.infisense.iruvc.utils.CommonParams;
import com.infisense.usbir.R;
import com.infisense.usbir.camera.IRUVC;

import java.io.IOException;
import java.io.InputStream;

import butterknife.BindView;
import butterknife.OnClick;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:53
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:53
 * @UpdateRemark:
 */
public class ToolActivity extends BaseActivity {
    private static final String TAG = "ToolActivity";
    @BindView(R.id.update)
    Button update;
    @BindView(R.id.progressBar)
    ProgressBar progressBar;
    @BindView(R.id.upstatus)
    TextView upstatus;
    private int cameraWidth = 256;
    private int cameraHeight = 384;
    private IRUVC iruvc;
    private Runnable runnable;
    // 是否使用IRISP算法集成
    private boolean isUseIRISP = false;
    private ActivityResultLauncher<Intent> activityResult;
    private Uri updateUri;

    /**
     *
     */
    private static final int FIRMWARE_UPDATE = 1000;
    private final Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(@NonNull Message msg) {
            super.handleMessage(msg);
            if (msg.what == FIRMWARE_UPDATE) {
                firmwareUpdateReadBytes(updateUri);
            }
        }
    };

    @Override
    protected int getContentView() {
        return R.layout.layout_tools;
    }

    @Override
    public void initView() {

    }

    @Override
    protected void init(Bundle savedInstanceState) {
        //
        runnable = new Runnable() {
            @Override
            public void run() {
                /**
                 * 再次调用此Runnable对象，以实现更新进度条的定时器操作
                 * 升级过程中没有进度的回调，但是升级所需要的时间基本固定，可以做一个假的进度条
                 */
                progressBar.setProgress(progressBar.getProgress() + 1);
                mHandler.postDelayed(this, 1700);
            }
        };
        // 注册监听ActivityResult
        activityResult = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), new ActivityResultCallback<ActivityResult>() {
            @Override
            public void onActivityResult(ActivityResult result) {
                //此处是跳转的result回调方法
                Log.d(TAG, "onActivityResult");
                if (result.getData() != null && result.getResultCode() == Activity.RESULT_OK) {
                    // 数据在此处理
                    upstatus.setText(getResources().getString(R.string.firmware_updating));
                    upstatus.setTextColor(ContextCompat.getColor(ToolActivity.this, R.color.red));
                    updateUri = result.getData().getData();
                    mHandler.sendEmptyMessageDelayed(FIRMWARE_UPDATE, 1000);
                }
            }
        });
    }

    @OnClick({R.id.update})
    public void onViewClicked(View view) {
        switch (view.getId()) {
            case R.id.update: {
                // 请求接口，测试设备连接状态
                // gain status
                int[] value = new int[1];
                iruvc.getIrcmd().getPropTPDParams(CommonParams.PropTPDParams.TPD_PROP_GAIN_SEL, value);
                Log.i(TAG, "TPD_PROP_GAIN_SEL=" + value[0]);
                if (value[0] == 0 || value[0] == 1) {
                    // 选择升级文件
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");//设置类型，我这里是任意类型，任意后缀的可以这样写。
                    intent.addCategory(Intent.CATEGORY_OPENABLE);// 只有设置了这个，返回的uri才能使用 getContentResolver().openInputStream(uri) 打开。
                    activityResult.launch(intent);
                } else {
                    Toast.makeText(ToolActivity.this, getResources().getString(R.string.firmware_noconnect), Toast.LENGTH_SHORT).show();
                }
                break;
            }
        }
    }

    /**
     * firmware update
     *
     * @param inUri
     * @throws IOException
     */
    private void firmwareUpdateReadBytes(Uri inUri) {
        Log.d(TAG, "readBytes->getPath=" + inUri.getPath());
        try {
            InputStream inputStream = getContentResolver().openInputStream(inUri);
            int len = inputStream.available();
            Log.d(TAG, "readBytes->len=" + len);
            byte[] buffer = new byte[256 * 1024];
            if (len != 256 * 1024) return;
            if (inputStream.read(buffer) != -1) {
                Log.d(TAG, "readBytes->length=" + buffer.length);
                new Thread(new Runnable() {

                    @Override
                    public void run() {
                        mHandler.postDelayed(runnable, 1000);
                        // 等待updateFirmware返回结果，会阻塞在这里
                        int status = iruvc.getIrcmd().updateFirmware(buffer, len);
                        Log.d(TAG, "readBytes->status=" + status);
                        mHandler.removeCallbacks(runnable);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                if (status == 0) {
                                    // success
                                    progressBar.setProgress(100);
                                    upstatus.setText(getResources().getString(R.string.firmware_success));
                                    upstatus.setTextColor(ContextCompat.getColor(ToolActivity.this, R.color.green));
                                } else {
                                    // fail
                                    progressBar.setProgress(0);
                                    upstatus.setText(getResources().getString(R.string.firmware_fail) + status);
                                    upstatus.setTextColor(ContextCompat.getColor(ToolActivity.this, R.color.red));
                                }
                            }
                        });
                    }
                }).start();
            }
            // io close
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        iruvc = new IRUVC(cameraHeight, cameraWidth, ToolActivity.this, null,
                CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT, isUseIRISP);
        iruvc.registerUSB();
    }

    @Override
    protected void onStop() {
        Log.d(TAG, "onStop");
        super.onStop();

    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "onDestroy");
        super.onDestroy();
        if (iruvc != null) {
            iruvc.unregisterUSB();
            iruvc.stopPreview();
        }
    }

}




