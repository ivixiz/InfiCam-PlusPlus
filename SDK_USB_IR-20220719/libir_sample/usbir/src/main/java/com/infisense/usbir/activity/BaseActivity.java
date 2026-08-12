package com.infisense.usbir.activity;

import android.content.pm.PackageManager;
import android.os.Bundle;

import androidx.annotation.LayoutRes;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.infisense.usbir.R;
import com.infisense.usbir.view.WaterMark;

import butterknife.ButterKnife;

/**
 * @ProjectName: ANDROID_IRUVC_SDK
 * @Package: com.infisense.usbir.activity
 * @ClassName: BaseActivity
 * @Description:
 * @Author: brilliantzhao
 * @CreateDate: 2022.3.23 17:30
 * @UpdateUser:
 * @UpdateDate: 2022.3.23 17:30
 * @UpdateRemark:
 * @Version: 1.0.0
 */
public abstract class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        //
        setContentView(getContentView());
        ButterKnife.bind(this);
        // 非release版本添加水印来区分
        if (!getResources().getBoolean(R.bool.isReleaseVersion)) {
            try {
                WaterMark.getInstance().show(this, getPackageManager().getPackageInfo(
                        getPackageName(), 0).versionName);
            } catch (PackageManager.NameNotFoundException e) {
                e.printStackTrace();
            }
        }
        //
        initView();
        init(savedInstanceState);
    }

    @LayoutRes
    protected abstract int getContentView();

    public abstract void initView();

    protected abstract void init(Bundle savedInstanceState);
}
