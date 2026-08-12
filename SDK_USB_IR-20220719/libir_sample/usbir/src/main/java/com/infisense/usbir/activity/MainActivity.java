package com.infisense.usbir.activity;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.infisense.usbir.R;

import butterknife.OnClick;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:52
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:52
 * @UpdateRemark:
 */
public class MainActivity extends BaseActivity {

    private final int REQUEST_PERMISSION = 1000;
    /**
     * targetSdk>=28之后，USBCamera需要有Camera权限动态申请才能打开
     */
    private String[] permissions = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.CAMERA};

    @Override
    protected int getContentView() {
        return R.layout.activity_main;
    }

    @Override
    public void initView() {

    }

    @Override
    protected void init(Bundle savedInstanceState) {
        checkPermissions(permissions);
    }

    /**
     * check Permissions
     *
     * @param permissions
     * @return
     */
    private boolean checkPermissions(String[] permissions) {
        boolean havePermission = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // PERMISSION_GRANTED
                havePermission = false;
                // REQUEST PERMISSION
                ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSION);
            }
        }
        return havePermission;
    }

    @OnClick({R.id.btnMonitorConnect, R.id.btnIRDisplay, R.id.btnImageTempDisplay, R.id.btnTools})
    public void onViewClicked(View v) {
        switch (v.getId()) {
            case R.id.btnMonitorConnect: {
                startActivity(new Intent(this, USBMonitorActivity.class));
                break;
            }
            case R.id.btnIRDisplay: {
                // 图像+温度出图，分辨率为256*384
                startActivity(new Intent(this, IRDisplayActivity.class));
                break;
            }
            case R.id.btnImageTempDisplay: {
                // 图像或温度出图，分辨率为256*192
                // 其中温度出图需要调用 startY16ModePreview 进行切换
                startActivity(new Intent(this, ImageOrTempDisplayActivity.class));
                break;
            }
            case R.id.btnTools: {
                startActivity(new Intent(this, ToolActivity.class));
                break;
            }
            default:
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        switch (requestCode) {
            case REQUEST_PERMISSION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // PERMISSION_GRANTED
                } else if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {
                    // PERMISSION_DENIED
                    Toast.makeText(this, "If you want to use this function, you need to provide the corresponding permissions for the application in the settings", Toast.LENGTH_LONG).show();
                }
                break;
        }
    }

}