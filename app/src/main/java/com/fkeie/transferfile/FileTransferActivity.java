package com.fkeie.transferfile;

import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileTransferActivity extends AppCompatActivity {
    private static final int PICK_FILE_REQUEST = 1;
    private static final int PICK_MULTIPLE_FILES = 2;

    private String serverUrl;
    private String mode; // "client" 或 "local"
    private RecyclerView recyclerView;
    private FileAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private Button btnUpload, btnUploadMultiple;
    private ExecutorService executor;
    private List<FileInfo> fileList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transfer);

        mode = getIntent().getStringExtra("mode");
        if ("client".equals(mode)) {
            String serverIp = getIntent().getStringExtra("server_ip");
            serverUrl = "http://" + serverIp + ":56789";
            setTitle("连接: " + serverIp);
        } else {
            serverUrl = "http://localhost:56789";
            setTitle("本机文件管理");
        }

        executor = Executors.newFixedThreadPool(3);
        initViews();
        loadFileList();
    }

    private void initViews() {
        recyclerView = findViewById(R.id.recyclerView);
        swipeRefresh = findViewById(R.id.swipeRefresh);
        btnUpload = findViewById(R.id.btnUpload);
        btnUploadMultiple = findViewById(R.id.btnUploadMultiple);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FileAdapter(this::downloadFile, this::deleteFile);
        recyclerView.setAdapter(adapter);

        swipeRefresh.setOnRefreshListener(this::loadFileList);

        btnUpload.setOnClickListener(v -> pickFile(false));
        btnUploadMultiple.setOnClickListener(v -> pickFile(true));

        // 本机模式隐藏上传按钮
        if ("local".equals(mode)) {
            btnUpload.setText("发送文件到电脑");
            btnUploadMultiple.setVisibility(Button.GONE);
        }
    }

    private void pickFile(boolean multiple) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        if (multiple) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, multiple ? PICK_MULTIPLE_FILES : PICK_FILE_REQUEST);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == PICK_FILE_REQUEST) {
            Uri uri = data.getData();
            if (uri != null) uploadFile(uri);
        } else if (requestCode == PICK_MULTIPLE_FILES) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int i = 0; i < count; i++) {
                    uploadFile(data.getClipData().getItemAt(i).getUri());
                }
            } else if (data.getData() != null) {
                uploadFile(data.getData());
            }
        }
    }

    private void uploadFile(Uri uri) {
        String fileName = getFileName(uri);
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("上传 " + fileName + "...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        executor.execute(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new IOException("无法读取文件");

                byte[] fileData = readAllBytes(is);
                is.close();

                // 构建multipart请求
                String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
                URL url = new URL(serverUrl + "/api/upload");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setDoOutput(true);
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);

                try (DataOutputStream dos = new DataOutputStream(conn.getOutputStream())) {
                    // 写入文件数据
                    dos.writeBytes("--" + boundary + "\r\n");
                    dos.writeBytes("Content-Disposition: form-data; name=\"files\"; filename=\"" + fileName + "\"\r\n");
                    dos.writeBytes("Content-Type: application/octet-stream\r\n\r\n");
                    dos.write(fileData);
                    dos.writeBytes("\r\n--" + boundary + "--\r\n");
                }

                int responseCode = conn.getResponseCode();
                runOnUiThread(() -> {
                    dialog.dismiss();
                    if (responseCode == 200) {
                        Toast.makeText(this, "上传成功", Toast.LENGTH_SHORT).show();
                        loadFileList();
                    } else {
                        Toast.makeText(this, "上传失败: " + responseCode, Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "上传错误: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void downloadFile(FileInfo fileInfo) {
        ProgressDialog dialog = new ProgressDialog(this);
        dialog.setMessage("下载 " + fileInfo.name + "...");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setCancelable(false);
        dialog.show();

        executor.execute(() -> {
            try {
                URL url = new URL(serverUrl + "/api/download/" + URLEncoder.encode(fileInfo.name, "UTF-8"));
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) throw new IOException("下载失败: " + responseCode);

                File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                File file = new File(downloadDir, fileInfo.name);

                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(file)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    long total = 0;
                    while ((read = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, read);
                        total += read;
                        int progress = (int) ((total * 100) / fileInfo.bytes);
                        runOnUiThread(() -> dialog.setProgress(progress));
                    }
                }

                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "已保存到: " + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
                });

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(this, "下载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void deleteFile(FileInfo fileInfo) {
        new AlertDialog.Builder(this)
            .setTitle("确认删除")
            .setMessage("确定要删除 " + fileInfo.name + " 吗？")
            .setPositiveButton("删除", (dialog, which) -> {
                executor.execute(() -> {
                    try {
                        URL url = new URL(serverUrl + "/api/delete/" + URLEncoder.encode(fileInfo.name, "UTF-8"));
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("DELETE");

                        int responseCode = conn.getResponseCode();
                        runOnUiThread(() -> {
                            if (responseCode == 200) {
                                Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
                                loadFileList();
                            } else {
                                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                            }
                        });

                    } catch (Exception e) {
                        e.printStackTrace();
                        runOnUiThread(() -> Toast.makeText(this, "删除错误", Toast.LENGTH_SHORT).show());
                    }
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void loadFileList() {
        swipeRefresh.setRefreshing(true);
        executor.execute(() -> {
            try {
                URL url = new URL(serverUrl + "/api/files");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");

                int responseCode = conn.getResponseCode();
                if (responseCode == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray jsonArray = new JSONArray(sb.toString());
                    fileList.clear();
                    for (int i = 0; i < jsonArray.length(); i++) {
                        JSONObject obj = jsonArray.getJSONObject(i);
                        FileInfo info = new FileInfo();
                        info.name = obj.getString("name");
                        info.size = obj.getString("size");
                        info.bytes = obj.getLong("bytes");
                        info.date = obj.getString("date");
                        fileList.add(info);
                    }

                    runOnUiThread(() -> {
                        adapter.setFiles(fileList);
                        swipeRefresh.setRefreshing(false);
                    });
                } else {
                    throw new IOException("HTTP " + responseCode);
                }

            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    Toast.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    private String getFileName(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) result = cursor.getString(index);
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result != null ? result : "unknown";
    }

    private byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        byte[] data = new byte[16384];
        while ((nRead = is.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
