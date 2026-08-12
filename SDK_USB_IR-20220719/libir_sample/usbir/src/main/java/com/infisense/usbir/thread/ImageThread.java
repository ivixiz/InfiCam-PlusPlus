package com.infisense.usbir.thread;

import android.graphics.Bitmap;
import android.os.SystemClock;
import android.util.Log;

import com.infisense.iruvc.sdkisp.LibIRParse;
import com.infisense.iruvc.sdkisp.LibIRProcess;
import com.infisense.iruvc.utils.CommonParams;
import com.infisense.iruvc.utils.SynchronizedBitmap;

import java.nio.ByteBuffer;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2022.2.24 11:06
 * @UpdateUser:
 * @UpdateDate:     2022.2.24 11:06
 * @UpdateRemark:
 */
public class ImageThread extends Thread {
    private String TAG = "ImageThread";
    private Bitmap bitmap;
    private SynchronizedBitmap syncimage;
    private int imageWidth;
    private int imageHeight;
    private byte[] imagesrc;
    private boolean rotate = false;
    //
    private CommonParams.PseudoColorType pseudocolorMode = CommonParams.PseudoColorType.PSEUDO_WHITE_HOT;
    //
    private CommonParams.DataFlowMode dataFlowMode = CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT;
    private byte[] imageYUV422;
    private byte[] imageARGB;
    private byte[] imageDst;

    /**
     * @param syncimage
     */
    public void setSyncimage(SynchronizedBitmap syncimage) {
        this.syncimage = syncimage;
    }

    /**
     * @param imagesrc
     */
    public void setImagesrc(byte[] imagesrc) {
        this.imagesrc = imagesrc;
    }

    /**
     * @param rotate
     */
    public void setRotate(boolean rotate) {
        this.rotate = rotate;
    }

    /**
     * @param imageWidth
     * @param imageHeight
     */
    public ImageThread(int imageWidth, int imageHeight) {
        Log.i(TAG, "ImageThread create->imageWidth = " + imageWidth + " imageHeight = " + imageHeight);
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        imageYUV422 = new byte[imageWidth * imageHeight * 2];
        imageARGB = new byte[imageWidth * imageHeight * 4];
        imageDst = new byte[imageWidth * imageHeight * 4];
    }

    /**
     * @param dataFlowMode
     */
    public void setDataFlowMode(CommonParams.DataFlowMode dataFlowMode) {
        this.dataFlowMode = dataFlowMode;
    }

    /**
     * @param pseudocolorMode
     */
    public void setPseudocolorMode(CommonParams.PseudoColorType pseudocolorMode) {
        this.pseudocolorMode = pseudocolorMode;
    }

    /**
     * @param bitmap
     */
    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    @Override
    public void run() {
        while (!isInterrupted()) {
            synchronized (syncimage.dataLock) {
                if (syncimage.start) {
//                    Log.i(TAG, "run->dataFlowMode = " + dataFlowMode + " pseudocolorMode = " +
//                            pseudocolorMode + " rotate = " + rotate + " syncimage.valid = " + syncimage.valid
//                            + " imagesrc.length = " + imagesrc.length+" imagesrc[100] = "+imagesrc[100]);
                    if (dataFlowMode == CommonParams.DataFlowMode.IMAGE_AND_TEMP_OUTPUT ||
                            dataFlowMode == CommonParams.DataFlowMode.IMAGE_OUTPUT) {
                        // yuv422格式
                        if (pseudocolorMode != null) {
                            LibIRProcess.convertYuyvMapToARGBPseudocolor(imagesrc, (long) imageHeight * imageWidth, pseudocolorMode, imageARGB);
                        } else {
                            LibIRParse.converyArrayYuv422ToARGB(imagesrc, imageHeight * imageWidth, imageARGB);
                        }
                    } else {
                        // 调用 startY16ModePreview 中间出图方法之后，输出的数据格式为y16,需要做转换
                        LibIRParse.convertArrayY14ToYuv422(imagesrc, imageHeight * imageWidth, imageYUV422);
                        if (pseudocolorMode != null) {
                            LibIRProcess.convertYuyvMapToARGBPseudocolor(imageYUV422, (long) imageHeight * imageWidth, pseudocolorMode, imageARGB);
                        } else {
                            LibIRParse.converyArrayYuv422ToARGB(imageYUV422, imageHeight * imageWidth, imageARGB);
                        }
                    }
                    if (rotate) {
                        LibIRProcess.ImageRes_t imageRes = new LibIRProcess.ImageRes_t();
                        imageRes.height = (char) imageWidth;
                        imageRes.width = (char) imageHeight;
                        LibIRProcess.rotateRight90(imageARGB, imageRes, CommonParams.IRPROCSRCFMTType.IRPROC_SRC_FMT_ARGB8888, imageDst);
                    } else {
                        imageDst = imageARGB;
                    }
                }
            }

            synchronized (syncimage.viewLock) {
                if (!syncimage.valid) {
                    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(imageDst));
                    syncimage.valid = true;
                    syncimage.viewLock.notify();
                }
            }
            SystemClock.sleep(20);
        }
        Log.i(TAG, "ImageThread exit");
    }

}