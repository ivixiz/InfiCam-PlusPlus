package com.infisense.usbir.bean;

import android.graphics.Bitmap;

public class ReginModeBean {
    private int pcColor;

    private int img;

    public Bitmap getBitmap() {
        return bitmap;
    }

    public void setBitmap(Bitmap bitmap) {
        this.bitmap = bitmap;
    }

    private Bitmap bitmap;

    public ReginModeBean(int pcColor, String titleName) {
        this.pcColor = pcColor;
        this.titleName = titleName;
    }

    public ReginModeBean(int img, int pcColor, String titleName) {
        this.img = img;
        this.pcColor = pcColor;
        this.titleName = titleName;
    }

    public int getPcColor() {
        return pcColor;
    }

    public void setPcColor(int pcColor) {
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
