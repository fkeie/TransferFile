package com.fkeie.transferfile;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final int MANAGE_STORAGE_REQUEST = 101;

    private TextView tvDeviceInfo;
    private EditText etServerIp;
    private Button btnStartServer, btnConnect, btnManageFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        checkPermissions();
        updateDeviceInfo();
    }

    private void initViews() {
        tvDeviceInfo = findViewById(R.id.tvDeviceInfo);
        etServerIp = findViewById(R.id.etServerIp);
        btnStartServer = findViewById(R.id.btnStartServer);
        btnConnect = findViewById(R.id.btnConnect);
        btnManageFiles = findViewById(R.id.btnManageFiles);

        // 启动服务器模式（手机作为服务器，电脑访问）
        btnStartServer.setOnClickListener(v -> {
            if (!NetworkUtils.isWifiConnected(this)) {
                Toast.makeText(this, "请先连接WiFi", Toast.LENGTH_SHORT).show();
                return;
            }
            startServerMode();
        });

        // 连接模式（手机作为客户端，连接电脑服务器）
        btnConnect.setOnClickListener(v -> {
            String ip = etServerIp.getText().toString().trim();
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入电脑IP地址", Toast.LENGTH_SHORT).show();
                return;
            }
            connectToServer(ip);
        });

        // 管理本机文件（浏览已接收的文件）
        btnManageFiles.setOnClickListener(v -> {
            Intent intent = new Intent(this, FileTransferActivity.class);
            intent.putExtra("mode", "local");
            startActivity(intent);
        });
    }

    private void startServerMode() {
        String ip = NetworkUtils.getLocalIpAddress();
        if (ip == null) {
            Toast.makeText(this, "无法获取IP地址", Toast.LENGTH_SHORT).show();
            return;
        }

        // 启动后台服务
        Intent serviceIntent = new Intent(this, FileService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        new AlertDialog.Builder(this)
            .setTitle("服务器已启动")
            .setMessage("请在电脑浏览器访问:\nhttp://" + ip + ":56789\n\n或点击\"管理文件\"查看")
            .setPositiveButton("复制地址", (dialog, which) -> {
                android.content.ClipboardManager clipboard = 
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IP", "http://" + ip + ":56789"));
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("知道了", null)
            .show();
    }

    private void connectToServer(String ip) {
        Intent intent = new Intent(this, FileTransferActivity.class);
        intent.putExtra("mode", "client");
        intent.putExtra("server_ip", ip);
        startActivity(intent);
    }

    private void updateDeviceInfo() {
        String ip = NetworkUtils.getLocalIpAddress();
        String wifiName = NetworkUtils.getWifiName(this);
        tvDeviceInfo.setText(String.format("WiFi: %s\n本机IP: %s", 
            wifiName != null ? wifiName : "未连接", 
            ip != null ? ip : "未知"));
    }

    private void checkPermissions() {
        // Android 11+ 需要特殊权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                startActivityForResult(intent, MANAGE_STORAGE_REQUEST);
            }
        } else {
            String[] permissions = {
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            for (String permission : permissions) {
                if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE);
                    break;
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == MANAGE_STORAGE_REQUEST) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    Toast.makeText(this, "存储权限已获取", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "需要存储权限才能传输文件", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "权限已获取", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "需要权限才能正常使用", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDeviceInfo();
    }
}
