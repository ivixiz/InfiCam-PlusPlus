package com.infisense.usbir.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import com.infisense.iruvc.utils.CommonParams;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * @ProjectName: ANDROID_IRUVC_SDK
 * @Package: com.infisense.iruvc.utils
 * @ClassName: FileUtil
 * @Description:
 * @Author: brilliantzhao
 * @CreateDate: 2021.11.11 13:56
 * @UpdateUser:
 * @UpdateDate: 2021.11.11 13:56
 * @UpdateRemark:
 * @Version: 1.0.0
 */
public class FileUtil {

    private static final String TAG = "FileUtil";

    /**
     * @param context
     * @return
     */
    public static String getDiskCacheDir(Context context) {
        String cachePath = context.getCacheDir().getPath();
        if (Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())
                || !Environment.isExternalStorageRemovable()) {
            File externalCacheDir = context.getExternalCacheDir();
            if (externalCacheDir != null) {
                cachePath = externalCacheDir.getPath();
            }
        }
        return cachePath;
    }

    /**
     * @param context
     * @param srcFileName
     * @param strOutFileName
     * @throws IOException
     */
    public static void copyAssetsDataToSD(Context context, String srcFileName, String strOutFileName) throws IOException {
        File file = new File(strOutFileName);
        if (file.exists()) {
            return;
        }
        InputStream myInput;
        OutputStream myOutput = new FileOutputStream(strOutFileName);
        myInput = context.getAssets().open(srcFileName);
        byte[] buffer = new byte[1024];
        int length = myInput.read(buffer);
        while (length > 0) {
            myOutput.write(buffer, 0, length);
            length = myInput.read(buffer);
        }
        myOutput.flush();
        myInput.close();
        myOutput.close();
    }

    /**
     * @param bytes
     * @param fileTitle
     */
    public static void saveByteFile(byte[] bytes, String fileTitle) {
        try {
            File path = new File("/sdcard");
            if (!path.exists() && path.isDirectory()) {
                path.mkdirs();
            }
            String filePath = "/sdcard/";
            String fileName = fileTitle + new SimpleDateFormat("_HHmmss_yyMMdd").
                    format(new Date(System.currentTimeMillis())) + ".bin";
            File file = new File(filePath, fileName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param bytes
     * @param fileTitle
     */
    public static void saveShortFile(short[] bytes, String fileTitle) {
        try {
            File path = new File("/sdcard");
            if (!path.exists() && path.isDirectory()) {
                path.mkdirs();
            }
            File file = new File("/sdcard/", fileTitle + new SimpleDateFormat("_HHmmss_yyMMdd").
                    format(new Date(System.currentTimeMillis())) + ".bin");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(toByteArray(bytes));
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 根据数据流获取Y16类型
     *
     * @param dataFlowMode
     * @return
     */
    public static CommonParams.Y16ModePreviewSrcType getY16SrcTypeByDataFlowMode(CommonParams.DataFlowMode dataFlowMode) {
        switch (dataFlowMode) {
            case TEMP_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_TEMPERATURE;
            }
            case IR_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_IR;
            }
            case KBC_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_KBC;
            }
            case HBC_DPC_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_HBC_DPC;
            }
            case VBC_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_VBC;
            }
            case TNR_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_TNR;
            }
            case SNR_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_SNR;
            }
            case AGC_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_AGC;
            }
            case DDE_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_DDE;
            }
            case GAMMA_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_GAMMA;
            }
            case MIRROR_OUTPUT: {
                return CommonParams.Y16ModePreviewSrcType.Y16_MODE_MIRROR;
            }
        }
        return null;
    }

    /**
     * 创建文件夹---之所以要一层层创建，是因为一次性创建多层文件夹可能会失败！
     *
     * @param dirFile
     * @return
     */
    public static boolean createFileDir(File dirFile) {
        if (dirFile == null) return true;
        if (dirFile.exists()) {
            return true;
        }
        File parentFile = dirFile.getParentFile();
        if (parentFile != null && !parentFile.exists()) {
            //父文件夹不存在，则先创建父文件夹，再创建自身文件夹
            return createFileDir(parentFile) && createFileDir(dirFile);
        } else {
            boolean mkdirs = dirFile.mkdirs();
            boolean isSuccess = mkdirs || dirFile.exists();
            if (!isSuccess) {
                Log.e("FileUtil", "createFileDir fail " + dirFile);
            }
            return isSuccess;
        }
    }

    /**
     * @param dirPath
     * @param fileName
     * @return
     */
    public static File createFile(String dirPath, String fileName) {
        try {
            File dirFile = new File(dirPath);
            if (!dirFile.exists()) {
                if (!createFileDir(dirFile)) {
                    Log.e(TAG, "createFile dirFile.mkdirs fail");
                    return null;
                }
            } else if (!dirFile.isDirectory()) {
                boolean delete = dirFile.delete();
                if (delete) {
                    return createFile(dirPath, fileName);
                } else {
                    Log.e(TAG, "createFile dirFile !isDirectory and delete fail");
                    return null;
                }
            }
            File file = new File(dirPath, fileName);
            if (!file.exists()) {
                if (!file.createNewFile()) {
                    Log.e(TAG, "createFile createNewFile fail");
                    return null;
                }
            }
            return file;
        } catch (Exception e) {
            Log.e(TAG, "createFile fail :" + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 把两个位图覆盖合成为一个位图，以底层位图的长宽为基准
     *
     * @param bytes  在底部的位图
     * @param bytes2 盖在上面的位图
     */
    public static void savaRawFile(byte[] bytes, byte[] bytes2) {
        try {
            File path = new File("/sdcard");
            if (!path.exists() && path.isDirectory()) {
                path.mkdirs();
            }
            File file = new File("/sdcard/", new SimpleDateFormat("_HHmmss_yyMMdd").
                    format(new Date(System.currentTimeMillis())) + ".bin");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.write(bytes2);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存红外数据
     *
     * @param bytes
     */
    public static void savaIRFile(byte[] bytes) {
        try {
            File path = new File("/sdcard");
            if (!path.exists() && path.isDirectory()) {
                path.mkdirs();
            }
            File file = new File("/sdcard/", "ir" + new SimpleDateFormat("_HHmmss_yyMMdd").
                    format(new Date(System.currentTimeMillis())) + ".bin");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 保存温度数据
     *
     * @param bytes
     */
    public static void savaTempFile(byte[] bytes) {
        try {
            File path = new File("/sdcard");
            if (!path.exists() && path.isDirectory()) {
                path.mkdirs();
            }
            File file = new File("/sdcard/", "temp" + new SimpleDateFormat("_HHmmss_yyMMdd").
                    format(new Date(System.currentTimeMillis())) + ".bin");
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(bytes);
            fos.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param context
     * @param file
     * @return
     */
    public static boolean isFileExists(Context context, final File file) {
        if (file == null) return false;
        if (file.exists()) {
            return true;
        }
        return isFileExists(context, file.getAbsolutePath());
    }

    /**
     * Return whether the file exists.
     *
     * @param filePath The path of file.
     * @return {@code true}: yes<br>{@code false}: no
     */
    public static boolean isFileExists(Context context, final String filePath) {
        File file = new File(filePath);
        if (file == null) return false;
        if (file.exists()) {
            return true;
        }
        return isFileExistsApi29(context, filePath);
    }

    /**
     * @param context
     * @param filePath
     * @return
     */
    private static boolean isFileExistsApi29(Context context, String filePath) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                Uri uri = Uri.parse(filePath);
                ContentResolver cr = context.getContentResolver();
                AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
                if (afd == null) return false;
                try {
                    afd.close();
                } catch (IOException ignore) {
                }
            } catch (FileNotFoundException e) {
                return false;
            }
            return true;
        }
        return false;
    }

    /**
     * short数组转byte数组
     *
     * @param src
     * @return
     */
    private static byte[] toByteArray(short[] src) {
        int count = src.length;
        byte[] dest = new byte[count << 1];
        for (int i = 0; i < count; i++) {
            dest[i * 2] = (byte) ((src[i] >> 8) & 0xFF);
            dest[i * 2 + 1] = (byte) (src[i] & 0xFF);
        }
        return dest;
    }

    /**
     * byte数组转short数组
     *
     * @param src
     * @return
     */
    public static short[] toShortArray(byte[] src) {
        int count = src.length >> 1;
        short[] dest = new short[count];
        for (int i = 0; i < count; i++) {
            dest[i] = (short) ((src[i * 2] & 0xFF) << 8 | ((src[2 * i + 1] & 0xFF)));
        }
        return dest;
    }

    /**
     * @param bytes
     * @param fileTitle
     */
    public static void saveShortFile(String fileDir, short[] bytes, String fileTitle) {
        // 创建目录
        createOrExistsDir(fileDir);
        try {
            File file = new File(fileDir, fileTitle + ".bin");
            createOrExistsDir(file);
            Log.i("TAG", "getAbsolutePath = " + file.getAbsolutePath());
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(toByteArray(bytes));
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * @param file
     */
    private static void createOrExistsDir(File file) {
        // 文件不存在则创建文件
        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 如果文件夹不存在则创建
     *
     * @param fileDir
     */
    private static void createOrExistsDir(String fileDir) {
        File file = new File(fileDir);
        //如果文件夹不存在则创建
        if (!file.exists() && !file.isDirectory()) {
            //不存在
            file.mkdir();
        } else {
            //目录存在
        }
    }

    private static int sBufferSize = 524288;

    /**
     * @param context
     * @param file
     * @return
     */
    public static byte[] readFile2BytesByStream(Context context, final File file) {
        if (!isFileExists(context, file)) return null;
        try {
            ByteArrayOutputStream os = null;
            InputStream is = new BufferedInputStream(new FileInputStream(file), sBufferSize);
            try {
                os = new ByteArrayOutputStream();
                byte[] b = new byte[sBufferSize];
                int len;
                while ((len = is.read(b, 0, sBufferSize)) != -1) {
                    os.write(b, 0, len);
                }
                return os.toByteArray();
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            } finally {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                try {
                    if (os != null) {
                        os.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            return null;
        }
    }

}
