# 项目简介

USB单光demo，可以在此基础上进行二次开发。

# 出图流程

本流程以libir_sample中的示例为基础进行讲解，您也可以按照您自己的理解实现自己的流程。

## Step1 

在文件`IRUVC.java`中，`USBMonitor`监听设备连接，当插入USB设备的时候，会进入到`onAttach`回调，在其中进行设备pid过滤(用户可以设置自己的过滤白名单)，如果在白名单中，则进行权限申请。

```java
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
```

## Step2

权限申请通过之后，会进入到Step1中`USBMonitor`类的`onConnect`回调，经过设备过滤和判断之后，会执行`openUVCCamera`函数来开启UVC并初始化：

```java
...
// uvc开启
uvcCamera.openUVCCamera(ctrlBlock, DEFAULT_PREVIEW_MIN_FPS, DEFAULT_PREVIEW_MAX_FPS);
...

...
ConcreateUVCBuilder concreateUVCBuilder = new ConcreateUVCBuilder();
uvcCamera = concreateUVCBuilder
        .setUVCType(UVCType.xxx)
        .setOutputWidth(cameraWidth)
        .setOutputHeight(cameraHeight)
        .build();
// IRCMD init
ConcreteIRCMDBuilder concreteIRCMDBuilder = new ConcreteIRCMDBuilder();
ircmd = concreteIRCMDBuilder
        .setIrcmdType(IRCMDType.xxx)
        .setIdCamera(uvcCamera.getNativePtr())
        .build();

...
```

执行`startPreview`函数来出图：

```java
...
uvcCamera.setOpenStatus(true);
uvcCamera.setFrameCallback(iFrameCallback);
...
uvcCamera.onStartPreview();
...
```

## Step3

出图之后，会进入到`IFrameCallback`回调函数`onFrame`中，该函数会返回机芯中的红外和温度数据：

```java
iFrameCallback = new IFrameCallback() {
    @Override
    public void onFrame(byte[] frame) {
    	// 处理红外和温度数据
    	...
    	}
    }
```

## Step4

在文件`ImageThread.java`中把Step3中的红外数据进行格式转换：

```java
...
// yuv422格式转为ARGB格式
if (pseudocolorMode != null) {
    LibIRProcess.convertYuyvMapToARGBPseudocolor(imagesrc, (long) imageHeight * imageWidth, pseudocolorMode, imageARGB);
} else {
    LibIRParse.converyArrayYuv422ToARGB(imagesrc, imageHeight * imageWidth, imageARGB);
}
...
```

和旋转翻转：

```java
...
if (rotate) {
    LibIRProcess.ImageRes_t imageRes = new LibIRProcess.ImageRes_t();
    imageRes.height = (char) imageWidth;
    imageRes.width = (char) imageHeight;
    LibIRProcess.rotateRight90(imageARGB, imageRes, CommonParams.IRPROCSRCFMTType.IRPROC_SRC_FMT_ARGB8888, imageDst);
} else {
    imageDst = imageARGB;
}
...
```

最后转为bitmap:

```java
...
bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageDst));
...
```

## Step5

在需要展示红外画面或温度的地方引入控件：

```xml
<!-- 红外出图图层 -->
<com.infisense.usbir.view.CameraView
    android:id="@+id/cameraView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />

<!-- 温度图层 -->
<com.infisense.usbir.view.TemperatureView
    android:id="@+id/temperatureView"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />
```

在`CameraView.java`中，线程中对图像进行放大到适应屏幕，然后使用Canvas绘制：

```java
/**
 * 图片缩放，这里简单的使用getWidth()作为宽，getHeight()作为高，可能会出现画面拉伸情况，
 * 实际使用的时候请参考设备的宽高按照设备的图像尺寸做等比例缩放
 */
Bitmap mScaledBitmap = Bitmap.createScaledBitmap(bitmap, getWidth(), getHeight(), true);
canvas.drawBitmap(mScaledBitmap, 0, 0, null);

...
```

在`TemperatureView.java`中传递数据：

```java
...

// 用来关联温度数据和TemperatureView,方便后面的点线框测温
irtemp.setTempData(temperature);

...
```

# 注意事项

## USB Hub操作

Usbcontorl和Usbjni这两个类以及他们所在的文件夹必须原封不同的复制到您的项目中，不能修改包名

<img src="C:\GitGerrit\ANDROID_IRUVC_SDK\Common_Source\img\20211013184136.png" style="zoom:80%;" />

## USB设备插拔监听

AndroidManifest中监听USB设备的插拔，需要添加

```xml
        <activity
            ... >

            <!-- 监听USB设备的插拔 -->
            <intent-filter>
                <action android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED" />
            </intent-filter>

            <meta-data
                android:name="android.hardware.usb.action.USB_DEVICE_ATTACHED"
                android:resource="@xml/device_filter" />
        </activity>
```

# 使用说明

## 电脑无线连接手机

为了方便调试，使用电脑无线连接手机，首先需要配置adb环境，之后连接手机，步骤如下：

### adb环境配置

若已经配置好可跳过该步骤，具体配置网上有大量参考文章，这里不做过多描述。

### 命令行连接手机

可以使用命令行的方式连接手机，步骤如下：

首先请确认在手机的设置中打开了<开发者选项>,<无线调试>,<USB安装>选项，后续步骤如下：

- 手机跟电脑连接同一个网络下;

- 使用usb线连接手机，并检查是否连接成功

  ```
  C:\Users\zhao_>adb devices
  List of devices attached
  nvmvsct46hor4xts        device
  ```

- 打开手机端口： adb tcpip 5555 （默认是5555端口，可自己修改）

  ```
  C:\Users\zhao_>adb tcpip 5555
  restarting in TCP mode port: 5555
  ```

- 查看手机ip地址：adb shell ip -f inet addr show wlan0

```csharp
C:\Users\zhao_>adb shell ip -f inet addr show wlan0
32: wlan0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP group default qlen 3000
    inet 192.168.2.21/24 brd 192.168.2.255 scope global wlan0
       valid_lft forever preferred_lft forever
```

- 连接设备：adb connect 192.xxx.xxx.xxx:5555 (前面打印的手机ip地址)

```
C:\Users\zhao_>adb connect 192.168.2.21:5555
connected to 192.168.2.21:5555

C:\Users\zhao_>adb devices
List of devices attached
nvmvsct46hor4xts        device
192.168.2.21:5555       device
```

- 拔掉usb线，并查看无线是否连接成功

```ruby
C:\Users\zhao_>adb devices
List of devices attached
192.168.2.21:5555       device
```

### AndroidStudio插件方式连接手机

使用命令行的方式连接手机相对繁琐，用户可以选择使用AndroidStudio插件来简化该步骤，如下：

- 首先进入AndroidStudio设置里面的插件中，搜索adb wifi，下载一个下载量比较高的对应的插件。

  <img src="C:\GitGerrit\ANDROID_IRUVC_SDK\Common_Source\img\202010181317298.png" style="zoom:60%;" />

- 然后用usb连接电脑。

- 使用wifi插件点击connect

- 拔掉usb，即可用wifi进行调试。

## targetSdk版本设置

targetSdk<28时可以正常的申请和获取USB设备权限

targetSdk>=28时需要授权`Manifest.permission.CAMERA`才可以获取USB设备权限

### 原因分析

USBMonitor的回调函数onAttach中，请求权限的方法如下：

```java
mUSBMonitor.requestPermission(device);
```

继续追踪

```
this.mUsbManager.requestPermission(device, this.mPermissionIntent);
```

继续追踪

```java
/**
...
Permission for USB devices of class UsbConstants.USB_CLASS_VIDEO for clients that target SDK Build.VERSION_CODES.P and above can be granted only if they have additionally the Manifest.permission.CAMERA permission.
...
**/
public void requestPermission(UsbDevice device, PendingIntent pi) 
```

## targetSdk版本>=28，在部分的android10手机上不出图

targetSdk版本>=28，在部分的android10手机上，插入sensor设备的时候，在USBMonitor的回调函数onAttach里面回去申请权限，结果无法弹出申请权限的弹框，也无法出图。

系统一直打印这个信息 

```java
UsbUserSettingsManager: Camera permission required for USB video class devices
```

这个帖子有介绍，https://blog.csdn.net/wangchao1412/article/details/102837371，是系统层的问题并且没有更新补丁。

https://github.com/saki4510t/UVCCamera/issues/535 也提到即使授权Camera 也没用，系统代码里面判断`mUserContext.checkCallingPermission(android.Manifest.permission.CAMERA）`永远不成功。

### 问题验证

经测试 ，该问题只在 android10版本的手机上会出现，并且，三星和LG的android10手机可以正常出图，OPPO，小米，1+的android10手机无法正常出图。

对比FLIR的设备，在红米9A(android10)设备上安装FLIR ONE这个app，插入FILR的设备，也无法正常的出图。

### 解决办法

- 第一种：设置targetSdk<28

  可以正常的出图

- 第二种：有某些特殊的需求，要求targetSdk>=28

  可以正常的使用targetSdk>=28上架，然后在应用中判断用户使用的设备版本，如果是android10的设备，则通过打补丁或热修复或应用内升级的方式，修改targetSdk<28

## 生成只包含指定ABI的apk

SDK中提供的默认架构为：`arm64-v8a, armeabi-v7a, x86, x86_64`

```css
从NDK R17开始只支持`arm64-v8a, armeabi-v7a, x86, x86_64`这四种架构的so库：
ABIs [armeabi] are not supported for platform. Supported ABIs are [arm64-v8a, armeabi-v7a, x86, x86_64]
```

如果想生成只包含指定ABI的apk，可以在app的build.gradle中配置如下：

```groovy
android {
    ...
    defaultConfig {
        ...
        // 生成包含指定平台的so库的apk
        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64'
        }
        ...
    }
    ...
}
```

# 问题解析

## 画面黑屏不出图

### 问题描述

插入sensor之后，画面没有任何反应，一直黑屏，不能正常出图

### 解决方法

- 请先进入"测试USBMonitor的简单连接"这个页面，进行设备的插拔，查看是否进入USBMonitor对应的回调

- 如果没有进入回调，请检查您的USBMonitor注册是否执行

- 如果进入了回调，请跟踪调试是否请求了权限，位置：IRUVC.java文件中`mUSBMonitor.requestPermission(device);`，

  是否进入到了IRUVC.java文件中IFrameCallback的回调`public void onFrame(byte[] frame)`

  是否进入到了CameraView.java的绘制线程

## 画面上有蒙层

### 问题描述

任何画面一直有下面的圈出来的阴影，如图所示：

<img src="C:\GitGerrit\ANDROID_IRUVC_SDK\Common_Source\img\20211014145717.jpg" style="zoom: 25%;" />

### 解决方法

#### 方法1

可能是快门异常

可以对着不同的背景打快门，看看是不是快门片没动，观察问题是否解决。

#### 方法2

可能是测试的时候对着圈出来的图像的物体进行了锅盖标定。

请对着均匀的温度面重新进行锅盖标定(标定完毕之后请重新做测温的二次标定)。

## 录制视频出现条纹

### 问题描述

如图所示，在预览的时候画面正常，但是在录制视频之后，画面会出现条纹，如下：

<img src="C:\GitGerrit\ANDROID_IRUVC_SDK\Common_Source\img\20211117103504.png" style="zoom:50%;" />

### 解决方法

- 原始的bitmap的宽高反掉了

  检查原始bitmap的宽高，如果反掉了则调换一下。

- 由于原始的画面如640x480的图片，为了适应屏幕进行缩放，如放大为884x663，这个时候画面在SurfaceView或TextureView中绘制是正常的，但是在进行视频编码的时候，编码器会自动的对奇数宽或高进行加一或减一转化为偶数的宽高，此时就会出现如上所示的条纹。

  在把原始图像放大后，进行奇数宽高判断，如果为奇数，则加一或减一像素如884x664，然后再传递到视频编码器中。

## 找不到libusb3803_hub.so

### 问题描述

如图所示，在编译运行SDK demo的时候日志中报错，如下：

<img src="C:\GitGerrit\ANDROID_IRUVC_SDK\Common_Source\img\20211220143532.png" />

### 解决方法

`libusb3803_hub.so`为系统自带的so库，用于解决特定机型的问题，如遇找不到该so库的问题，可以注释掉代码中`Usbcontorl`类的对应调用。

## 探测器响应率偏高或偏低

### 问题描述

在高增益或低增益下，出现探测器响应率偏高或偏低。

响应率偏高：高温目标测出温度偏高，低温目标测出温度偏低；响应率偏低与之相反。

### 解决方法

响应偏高或者偏低目前没有软件调节的方法，非常严重的那种可以找技术支持或者品质反馈，判定确实是响应异常可以算作不良品的。

目前SDK及I2C指令都没有支持探测器配置的修改，而且模组阶段如果改了响应，那所有的图像参数及测温标定都会失效，需要重标。

## 自动快门相关

### 问题描述

- 如何设置自动快门的开关及时间间隔？各个参数的含义？

### 解决方法

请参考`IRCMD`中的`setPropAutoShutterParameter`方法，具体参数及含义请在`doc`文件夹下`index.html`中查找

## 自动增益切换相关

### 问题描述

如何设置自动增益切换的开关和切换？各个参数的含义？

### 解决方法

自动增益切换适用于**具有高低增益的模组**，高增益和低增益的测温范围不同，当需要测温的物体不在当前增益的测温范围内时，开启该功能后会根据设置的参数判断是否满足切换的条件，满足的话会自动切换模组的增益状态，并回调。

**备注：部分单高增益的模组不适用。**

参数及调用方法如下：

```java
...
private boolean auto_gain_switch = true;
private LibIRProcess.AutoGainSwitchInfo_t auto_gain_switch_info = new LibIRProcess.AutoGainSwitchInfo_t();
private LibIRProcess.GainSwitchParam_t gain_switch_param = new LibIRProcess.GainSwitchParam_t();

...

// 自动增益切换参数auto gain switch parameter
gain_switch_param.above_pixel_prop = 0.1f;    //用于high -> low gain,设备像素总面积的百分比
gain_switch_param.above_temp_data = (int) ((130 + 273.15) * 16 * 4); //用于high -> low gain,高增益向低增益切换的触发温度
gain_switch_param.below_pixel_prop = 0.95f;   //用于low -> high gain,设备像素总面积的百分比
gain_switch_param.below_temp_data = (int) ((110 + 273.15) * 16 * 4);//用于low -> high gain,低增益向高增益切换的触发温度
auto_gain_switch_info.switch_frame_cnt = 5 * 15; //连续满足触发条件帧数超过该阈值会触发自动增益切换(假设出图速度为15帧每秒，则5 * 15大概为5秒)
auto_gain_switch_info.waiting_frame_cnt = 7 * 15;//触发自动增益切换之后，会间隔该阈值的帧数不进行增益切换监测(假设出图速度为15帧每秒，则7 * 15大概为7秒)

...

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
```

## 测温修正相关

### 问题描述

测温修正接口如何使用？参数含义及如何设置？各参数的单位？

### 解决方法

测温修正的详细使用见 `用户开发标定 User calibration instructions->环境变量修正Ambient variable correction->环境变量修正Ambient variable correction.pdf`

## 锅盖标定相关

### 问题描述

如何进行锅盖标定，标定流程是什么？重新标定锅盖是否需要重置之前的？

### 解决方法

锅盖标定的具体流程，见文档 `用户开发标定 User calibration instructions->测温与锅盖标定Secondary calibration& Lid pattern noise correction->锅盖标定Lid pattern noise correction.pdf`

## 最高温，最低温以及中心点温度相关

### 问题描述

如何获取最高温，最低温和中心点温度？

### 解决方法

获取温度有两种方式，如下：

#### 方式一：从机芯返回的温度数据中，获取温度信息

使用到LibIRTemp类，使用方式见`TemperatureView.java`中

具体如下：

```java
...
private LibIRTemp irtemp;
...
    
irtemp = new LibIRTemp(imageWidth, imageHeight);  
...
    
// 用来关联温度数据
irtemp.setTempData(temperature);
...
```

使用到的具体函数如下：

```java
/**
 * 设置温度数据【copy Temperature data from buffer】<br/>
 *
 * @param src Temperature buffer
 */
public void setTempData(byte[] src)

	/**
     * 获取线的温度（包括最大值，最小值及坐标，平均值）【Get the temperature of the line (including maximum, minimum and coordinates, average)】<br/>
     * (units:Celsius)
     *
     * @param line Temperature coordinates
     * @return TemperatureSampleResult
     */
    public TemperatureSampleResult getTemperatureOfLine(Line line)
    
	/**
     * 获取框的温度（包括最大值，最小值及坐标，平均值）【Get the temperature of the frame (including maximum, minimum and coordinates, average)】<br/>
     * (units:Celsius)
     *
     * @param rect Rectangular area coordinates
     * @return TemperatureSampleResult
     */
    public TemperatureSampleResult getTemperatureOfRect(Rect rect)
```

#### 方式二：直接从机芯中获取温度信息

使用到IRCMD类，使用方式见`PopupCalibration.java`中

具体如下：

```java
/**
 * 获取点测温的温度信息【Get the point temperature information】<br/>
 * Please make sure the pointX and PointY is the sensor's real point<br/>
 *
 * @param pixelPointX      The point pixel's x location
 * @param pixelPointY      The point pixel's y location
 * @param temperatureValue length:1 units:Kelvin
 * @return see {@link IrcmdResult}
 */
public int getPointTemperatureInfo(int pixelPointX, int pixelPointY, int[] temperatureValue)

	/**
     * 获取线测温的温度信息（包括最大值，最小值及坐标，平均值）
     * 【Get the line temperature information(including maximum, minimum and coordinates, average)】<br/>
     * Please make sure the pointX and PointY is the sensor's real point<br/>
     *
     * @param startPointX      框的左上角x坐标
     * @param startPointY      框的左上角y坐标
     * @param endPointX        框的右下角x坐标
     * @param endPointY        框的右下角y坐标
     * @param temperatureValue length:7<br/>
     *                         temperatureValue[0]:ave_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[3]:max_temp_point.x;<br/>
     *                         temperatureValue[4]:max_temp_point.y;<br/>
     *                         temperatureValue[5]:min_temp_point.x;<br/>
     *                         temperatureValue[6]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getLineTemperatureInfo(int startPointX, int startPointY, int endPointX, int endPointY, int[] temperatureValue)

	/**
     * 获取框测温的温度信息（包括最大值，最小值及坐标，平均值）
     * 【Get the rectangle temperature information(including maximum, minimum and coordinates, average)】<br/>
     * Please make sure the pointX and PointY is the sensor's real point<br/>
     *
     * @param startPointX      框的左上角x坐标
     * @param startPointY      框的左上角y坐标
     * @param endPointX        框的右下角x坐标
     * @param endPointY        框的右下角y坐标
     * @param temperatureValue length:7<br/>
     *                         temperatureValue[0]:ave_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[3]:max_temp_point.x;<br/>
     *                         temperatureValue[4]:max_temp_point.y;<br/>
     *                         temperatureValue[5]:min_temp_point.x;<br/>
     *                         temperatureValue[6]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getRectTemperatureInfo(int startPointX, int startPointY, int endPointX, int endPointY,
                                      int[] temperatureValue)
    
	/**
     * 获取整帧的最大最小温度信息【Get the maximum and minimum temperature information of the frame】<br/>
     *
     * @param temperatureValue length:6<br/>
     *                         temperatureValue[0]:max_temp; units:Kelvin<br/>
     *                         temperatureValue[1]:min_temp; units:Kelvin<br/>
     *                         temperatureValue[2]:max_temp_point.x;<br/>
     *                         temperatureValue[3]:max_temp_point.y;<br/>
     *                         temperatureValue[4]:min_temp_point.x;<br/>
     *                         temperatureValue[5]:min_temp_point.y;<br/>
     * @return see {@link IrcmdResult}
     */
    public int getCurrentFrameMaxAndMinTemperature(int[] temperatureValue)
```

