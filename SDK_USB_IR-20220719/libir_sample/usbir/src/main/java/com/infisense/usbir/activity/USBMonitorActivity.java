package com.infisense.usbir.activity;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.yt.jni.Usbcontorl;

import androidx.appcompat.app.AppCompatActivity;
import androidx.databinding.DataBindingUtil;

import com.infisense.iruvc.usb.USBMonitor;
import com.infisense.usbir.R;
import com.infisense.usbir.databinding.ActivityUsbmonitorConnectBinding;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.28 16:53
 * @UpdateUser:
 * @UpdateDate:     2022.2.28 16:53
 * @UpdateRemark:
 */
public class USBMonitorActivity extends AppCompatActivity {

    private String TAG = "USBMonitorActivity";
    private ActivityUsbmonitorConnectBinding binding;
    private USBMonitor mUSBMonitor;
    private final int MESSAGE_WHAT_SHOWLOG = 1000;
    private String logContent = "";

    private Handler mHandler = new Handler(Looper.myLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (MESSAGE_WHAT_SHOWLOG == msg.what) {
                logContent += (String) msg.obj + "\n";
                binding.tvContent.setText(logContent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.w(TAG, "onCreate");
        binding = DataBindingUtil.setContentView(this, R.layout.activity_usbmonitor_connect);
        //
        initData();
    }

    /**
     *
     */
    private void initData() {
        // 注意：USBMonitor的所有回调函数都是运行在线程中的,请勿在回调函数中直接修改UI
        mUSBMonitor = new USBMonitor(this, new USBMonitor.OnDeviceConnectListener() {

            // called by checking usb device
            // do request device permission
            @Override
            public void onAttach(UsbDevice device) {
                Log.w(TAG, "USBMonitor->onAttach");
                //
                Message message = new Message();
                message.what = MESSAGE_WHAT_SHOWLOG;
                message.obj = "onAttach:getProductId=" + device.getProductId() +
                        " getVendorId=" + device.getVendorId();
                mHandler.sendMessage(message);
                // requestPermission
                mUSBMonitor.requestPermission(device);
            }

            // called by connect to usb camera
            // do open camera,start previewing
            @Override
            public void onConnect(final UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
                Log.w(TAG, "USBMonitor->onConnect");
                //
                Message message = new Message();
                message.what = MESSAGE_WHAT_SHOWLOG;
                message.obj = "onConnect";
                mHandler.sendMessage(message);
            }

            // called by disconnect to usb camera
            // do nothing
            @Override
            public void onDisconnect(UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
                Log.w(TAG, "USBMonitor->onDisconnect");
                //
                Message message = new Message();
                message.what = MESSAGE_WHAT_SHOWLOG;
                message.obj = "onDisconnect";
                mHandler.sendMessage(message);
            }

            // called by taking out usb device
            // do close camera
            @Override
            public void onDettach(UsbDevice device) {
                Log.w(TAG, "USBMonitor->onDettach");
                //
                Message message = new Message();
                message.what = MESSAGE_WHAT_SHOWLOG;
                message.obj = "onDettach";
                mHandler.sendMessage(message);
            }

            @Override
            public void onCancel(UsbDevice device) {
                Log.w(TAG, "USBMonitor->onCancel");
                //
                Message message = new Message();
                message.what = MESSAGE_WHAT_SHOWLOG;
                message.obj = "onCancel";
                mHandler.sendMessage(message);
            }
        });
    }

    @Override
    protected void onRestart() {
        super.onRestart();
        Log.w(TAG, "onRestart");
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.w(TAG, "onStart");
        registerUSB();
        // 某些特定客户的特殊设备需要使用该命令关闭或打开sensor的供电
        if (Usbcontorl.isLoad) {
            Usbcontorl.setUSB3803Mode(true);//打开sensor供电
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.w(TAG, "onResume");
    }

    /**
     *
     */
    private void registerUSB() {
        if (mUSBMonitor != null) {
            mUSBMonitor.register();
        }
    }

    /**
     *
     */
    private void unregisterUSB() {
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.w(TAG, "onPause");
    }

    @Override
    protected void onStop() {
        super.onStop();
        Log.w(TAG, "onStop");
        unregisterUSB();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.w(TAG, "onDestroy");
        // 某些特定客户的特殊设备需要使用该命令关闭或打开sensor的供电
        if (Usbcontorl.isLoad) {
            Usbcontorl.setUSB3803Mode(false);//关闭sensor供电
        }
    }
}