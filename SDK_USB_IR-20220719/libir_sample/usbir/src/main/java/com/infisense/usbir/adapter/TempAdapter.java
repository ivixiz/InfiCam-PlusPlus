package com.infisense.usbir.adapter;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.infisense.usbir.R;
import com.infisense.usbir.bean.ReginModeBean;

import java.util.ArrayList;

import butterknife.BindView;
import butterknife.ButterKnife;

/*
 * @Description:
 * @Author:         brilliantzhao
 * @CreateDate:     2021.12.10 10:20
 * @UpdateUser:
 * @UpdateDate:     2021.12.10 10:20
 * @UpdateRemark:
 */
public class TempAdapter extends RecyclerView.Adapter<TempAdapter.ViewHolder> {

    private Context context;
    private ArrayList<ReginModeBean> mDataList;
    private OnItemOnclickListenter listenter;

    public interface OnItemOnclickListenter {
        void onClick(int position);
    }

    /**
     * @param context
     * @param mMyLiveList
     * @param listenter
     */
    public TempAdapter(Context context, ArrayList<ReginModeBean> mMyLiveList, OnItemOnclickListenter listenter) {
        this.context = context;
        this.mDataList = mMyLiveList;
        this.listenter = listenter;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_temp_filter, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, @SuppressLint("RecyclerView") int position) {
        ReginModeBean filterBean = mDataList.get(position);
        holder.tvName.setText(filterBean.getTitleName());
        holder.Background_iv.setImageResource(filterBean.getImg());

        holder.rlRoot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                listenter.onClick(position);
            }
        });
    }

    @Override
    public int getItemCount() {
        return mDataList.size();
    }

    /**
     *
     */
    class ViewHolder extends RecyclerView.ViewHolder {

        @BindView(R.id.textureView)
        TextureView textureView;
        @BindView(R.id.tv_Name)
        TextView tvName;
        @BindView(R.id.Background_iv)
        ImageView Background_iv;
        @BindView(R.id.rl_root)
        RelativeLayout rlRoot;

        ViewHolder(View view) {
            super(view);
            ButterKnife.bind(this, view);
        }
    }
}
