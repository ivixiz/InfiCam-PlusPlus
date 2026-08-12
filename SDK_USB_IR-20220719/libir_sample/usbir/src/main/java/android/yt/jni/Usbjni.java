package android.yt.jni;

import android.util.Log;

/*
 * @Description:    为特定客户提供的USB插拔工具类,usb3803_hub是系统中的so库，部分定制的机型有可能会添加应用包名的白名单，也会导致不出图
 * @Author:         brilliantzhao
 * @CreateDate:     2022.3.21 9:27
 * @UpdateUser:
 * @UpdateDate:     2022.3.21 9:27
 * @UpdateRemark:
 */
public class Usbjni {

    private static final String TAG = "Usbjni";

    static {
        try {
            System.loadLibrary("usb3803_hub");
        } catch (UnsatisfiedLinkError e) {
            e.printStackTrace();
            Log.e(TAG, "Couldn't load lib:   - " + e.getMessage());
        }
    }

    /**
     * usb3803电源控制
     * 些特定客户的特殊设备需要使用该命令关闭或打开sensor的供电
     *
     * @param isPowerOn
     * @return
     */
    public static int setUSB3803Mode(boolean isPowerOn) {
        if (isPowerOn) {
            //打开
            return usb3803_mode_setting(1);
        } else {
            //关闭
            return usb3803_mode_setting(0);
        }
    }

    /**
     * @param i
     * @return
     */
    public static int readUSB3803Parameter(int i) {
        return usb3803_read_parameter(i);
    }


    static native int usb3803_mode_setting(int i);

    static native int usb3803_read_parameter(int i);
}
