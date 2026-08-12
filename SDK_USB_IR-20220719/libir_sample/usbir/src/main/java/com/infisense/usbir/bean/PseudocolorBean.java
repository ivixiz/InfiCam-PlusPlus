package com.infisense.usbir.bean;

import android.graphics.Bitmap;

import com.infisense.iruvc.utils.CommonParams;

public class PseudocolorBean {

    private CommonParams.PseudoColorType pcColor;

    private int img;

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    private Bitmap bitmap;

    public PseudocolorBean(CommonParams.PseudoColorType pcColor, String titleName) {
        this.pcColor = pcColor;
        this.titleName = titleName;
    }

    public PseudocolorBean(int img, CommonParams.PseudoColorType pcColor, String titleName) {
        this.img = img;
        this.pcColor = pcColor;
        this.titleName = titleName;
    }

    public CommonParams.PseudoColorType getPcColor() {
        return pcColor;
    }

    public void setPcColor(CommonParams.PseudoColorType pcColor) {
        this.pcColor = pcColor;
    }

    public String getTitleName() {
        return titleName;
    }

    public void setTitleName(String titleName) {
        this.titleName = titleName;
    }

    private String titleName;

    public int getImg() {
        return img;
    }

    public void setImg(int img) {
        this.img = img;
    }
}
