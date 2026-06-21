package com.fkeie.transferfile;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
    private List<FileInfo> files = new ArrayList<>();
    private final OnFileActionListener downloadListener;
    private final OnFileActionListener deleteListener;

    public interface OnFileActionListener {
        void onAction(FileInfo file);
    }

    public FileAdapter(OnFileActionListener download, OnFileActionListener delete) {
        this.downloadListener = download;
        this.deleteListener = delete;
    }

    public void setFiles(List<FileInfo> files) {
        this.files = files;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileInfo file = files.get(position);
        holder.tvName.setText(file.name);
        holder.tvMeta.setText(String.format("?? %s  ·  ?? %s", file.size, file.date));
        
        holder.btnDownload.setOnClickListener(v -> downloadListener.onAction(file));
        holder.btnDelete.setOnClickListener(v -> deleteListener.onAction(file));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvMeta;
        Button btnDownload, btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
            tvMeta = itemView.findViewById(R.id.tvMeta);
            btnDownload = itemView.findViewById(R.id.btnDownload);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}