package com.fkeie.transferfile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileService extends Service {  // ✅ 类名改为 FileService
    private static final String CHANNEL_ID = "FileTransferChannel";
    private static final int PORT = 56789;
    private static final String UPLOAD_DIR = "TransferFile";

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newFixedThreadPool(10);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification("正在启动服务器..."));
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        running = true;
        executor.execute(() -> {
            try {
                File uploadDir = new File(Environment.getExternalStorageDirectory(), UPLOAD_DIR);
                if (!uploadDir.exists()) {
                    uploadDir.mkdirs();
                }

                serverSocket = new ServerSocket(PORT);
                String ip = NetworkUtils.getLocalIpAddress();
                updateNotification("服务器运行中 http://" + ip + ":" + PORT);

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.execute(() -> handleClient(client, uploadDir));
                    } catch (IOException e) {
                        if (running) {
                            e.printStackTrace();
                        }
                    }
                }

            } catch (IOException e) {
                e.printStackTrace();
                updateNotification("服务器启动失败: " + e.getMessage());
            }
        });
    }

    private void handleClient(Socket client, File uploadDir) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(client.getInputStream()));
             OutputStream output = client.getOutputStream()) {

            String requestLine = reader.readLine();
            if (requestLine == null) return;

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            String method = parts[0];
            String path = URLDecoder.decode(parts[1], "UTF-8");

            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            if (path.equals("/") || path.equals("/index.html")) {
                sendResponse(output, 200, "text/html; charset=utf-8", getHtmlPage().getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/api/files")) {
                handleFileList(output, uploadDir);
            } else if (path.equals("/api/upload") && method.equals("POST")) {
                handleUpload(reader, output, uploadDir, contentLength);
            } else if (path.startsWith("/api/download/")) {
                String filename = path.substring("/api/download/".length());
                handleDownload(output, uploadDir, filename);
            } else if (path.startsWith("/api/delete/") && method.equals("DELETE")) {
                String filename = path.substring("/api/delete/".length());
                handleDelete(output, uploadDir, filename);
            } else {
                sendResponse(output, 404, "application/json", "{\"error\":\"Not Found\"}".getBytes());
            }

        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                client.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void handleFileList(OutputStream output, File uploadDir) throws IOException {
        StringBuilder json = new StringBuilder("[");
        File[] files = uploadDir.listFiles(File::isFile);
        if (files != null) {
            for (int i = 0; i < files.length; i++) {
                if (i > 0) json.append(",");
                File f = files[i];
                json.append(String.format(
                    "{\"name\":\"%s\",\"size\":\"%s\",\"bytes\":%d,\"date\":\"%s\"}",
                    escapeJson(f.getName()),
                    formatSize(f.length()),
                    f.length(),
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(new Date(f.lastModified()))
                ));
            }
        }
        json.append("]");
        sendResponse(output, 200, "application/json; charset=utf-8", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private void handleUpload(BufferedReader reader, OutputStream output, File uploadDir, int contentLength) throws IOException {
        char[] buffer = new char[contentLength];
        int read = 0;
        while (read < contentLength) {
            int result = reader.read(buffer, read, contentLength - read);
            if (result == -1) break;
            read += result;
        }
        String data = new String(buffer);

        String filename = "uploaded_" + System.currentTimeMillis();
        int nameStart = data.indexOf("filename=\"");
        if (nameStart > 0) {
            nameStart += 10;
            int nameEnd = data.indexOf("\"", nameStart);
            if (nameEnd > nameStart) {
                filename = sanitizeFilename(data.substring(nameStart, nameEnd));
            }
        }

        int contentStart = data.indexOf("\r\n\r\n") + 4;
        int contentEnd = data.lastIndexOf("\r\n------");
        if (contentEnd > contentStart) {
            String fileContent = data.substring(contentStart, contentEnd);
            File file = new File(uploadDir, filename);
            try (FileWriter fw = new FileWriter(file)) {
                fw.write(fileContent);
            }
        }

        sendResponse(output, 200, "application/json", 
            "{\"success\":true,\"message\":\"上传成功\"}".getBytes());
    }

    private void handleDownload(OutputStream output, File uploadDir, String filename) throws IOException {
        File file = new File(uploadDir, sanitizeFilename(filename));
        if (!file.exists() || !file.isFile()) {
            sendResponse(output, 404, "application/json", "{\"error\":\"File not found\"}".getBytes());
            return;
        }

        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"" + URLEncoder.encode(file.getName(), "UTF-8") + "\"\r\n" +
                "Content-Length: " + file.length() + "\r\n" +
                "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void handleDelete(OutputStream output, File uploadDir, String filename) throws IOException {
        File file = new File(uploadDir, sanitizeFilename(filename));
        if (file.exists() && file.delete()) {
            sendResponse(output, 200, "application/json", "{\"success\":true}".getBytes());
        } else {
            sendResponse(output, 404, "application/json", "{\"error\":\"File not found\"}".getBytes());
        }
    }

    private void sendResponse(OutputStream output, int status, String contentType, byte[] data) throws IOException {
        String statusText = status == 200 ? "OK" : (status == 404 ? "Not Found" : "Error");
        String header = "HTTP/1.1 " + status + " " + statusText + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        output.write(header.getBytes(StandardCharsets.UTF_8));
        output.write(data);
    }

    private String getHtmlPage() {
        return "<!DOCTYPE html><html lang=\"zh-CN\"><head>" +
                "<meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">" +
                "<title>TransferFile 文件传输</title>" +
                "<style>" +
                "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;" +
                "padding:20px;max-width:600px;margin:0 auto;background:#f5f5f5}" +
                "h1{color:#333;font-size:24px;margin-bottom:8px}" +
                ".subtitle{color:#666;font-size:14px;margin-bottom:24px}" +
                ".card{background:white;border-radius:12px;padding:20px;margin-bottom:15px;box-shadow:0 2px 8px rgba(0,0,0,0.1)}" +
                "h2{font-size:18px;color:#333;margin-bottom:15px}" +
                ".upload-area{border:2px dashed #ccc;border-radius:8px;padding:30px;text-align:center;cursor:pointer;transition:all 0.3s}" +
                ".upload-area:hover{border-color:#4CAF50;background:#f8fff8}" +
                "input[type=file]{display:none}" +
                ".btn{background:#4CAF50;color:white;border:none;padding:10px 20px;border-radius:6px;cursor:pointer;font-size:14px}" +
                ".btn:hover{background:#45a049}" +
                ".file-item{display:flex;justify-content:space-between;align-items:center;padding:12px;border-bottom:1px solid #eee}" +
                ".file-item:last-child{border-bottom:none}" +
                ".file-info{flex:1;min-width:0}" +
                ".file-name{font-weight:500;color:#333;font-size:15px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}" +
                ".file-meta{color:#999;font-size:12px;margin-top:4px}" +
                ".btn-small{padding:6px 12px;font-size:12px;border-radius:4px;border:none;cursor:pointer;margin-left:5px}" +
                ".btn-download{background:#2196F3;color:white}" +
                ".btn-delete{background:#ffebee;color:#d32f2f}" +
                ".empty-state{text-align:center;color:#999;padding:40px}" +
                ".progress{width:100%;height:4px;background:#e0e0e0;border-radius:2px;margin-top:10px;display:none}" +
                ".progress-bar{height:100%;background:#4CAF50;width:0%;transition:width 0.3s}" +
                "</style></head><body>" +
                "<h1>📁 TransferFile 文件传输</h1>" +
                "<p class=\"subtitle\">手机文件服务器</p>" +
                "<div class=\"card\"><h2>📤 上传文件</h2>" +
                "<div class=\"upload-area\" onclick=\"document.getElementById('fileInput').click()\">" +
                "<input type=\"file\" id=\"fileInput\" multiple onchange=\"uploadFiles(this.files)\">" +
                "<p>☁️ 点击选择文件 或 拖拽到此处</p>" +
                "<p style=\"color:#999;font-size:12px;margin-top:8px\">支持多文件上传</p></div>" +
                "<div class=\"progress\" id=\"progress\"><div class=\"progress-bar\" id=\"progressBar\"></div></div>" +
                "</div>" +
                "<div class=\"card\"><h2>📥 文件列表</h2><div id=\"fileList\">加载中...</div></div>" +
                "<script>" +
                "function uploadFiles(files){if(!files.length)return;" +
                "var progress=document.getElementById('progress');var bar=document.getElementById('progressBar');" +
                "progress.style.display='block';var uploaded=0;" +
                "for(var i=0;i<files.length;i++){" +
                "var formData=new FormData();formData.append('files',files[i]);" +
                "var xhr=new XMLHttpRequest();" +
                "xhr.upload.addEventListener('progress',function(e){" +
                "if(e.lengthComputable){var percent=((uploaded+e.loaded/e.total)/files.length)*100;" +
                "bar.style.width=percent+'%'}});" +
                "xhr.onload=function(){uploaded++;if(uploaded===files.length){bar.style.width='100%';" +
                "setTimeout(function(){progress.style.display='none';bar.style.width='0%';loadFiles()},1000)}};" +
                "xhr.open('POST','/api/upload');xhr.send(formData)}}" +
                "function loadFiles(){" +
                "fetch('/api/files').then(function(r){return r.json()}).then(function(files){" +
                "var list=document.getElementById('fileList');" +
                "if(!files.length){list.innerHTML='<div class=empty-state>暂无文件</div>';return}" +
                "list.innerHTML=files.map(function(f){" +
                "return'<div class=file-item><div class=file-info><div class=file-name>'+f.name+'</div>'+" +
                "'<div class=file-meta>📦 '+f.size+' · 🕐 '+f.date+'</div></div>'+" +
                "'<div><button class=\"btn-small btn-download\" onclick=\"location.href=\\'/api/download/'+encodeURIComponent(f.name)+'\\'\">⬇️ 下载</button>'+" +
                "'<button class=\"btn-small btn-delete\" onclick=\"deleteFile(\\''+encodeURIComponent(f.name)+'\\')\">🗑️</button></div></div>'}).join('')})}" +
                "function deleteFile(name){if(!confirm('确定删除?'))return;" +
                "fetch('/api/delete/'+name,{method:'DELETE'}).then(function(){loadFiles()})}" +
                "loadFiles();setInterval(loadFiles,5000)" +
                "</script></body></html>";
    }

    private String sanitizeFilename(String filename) {
        if (filename == null || filename.isEmpty()) {
            return "unnamed_file";
        }
        return filename.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.CHINA, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[Math.min(digitGroups, units.length - 1)]);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private Notification buildNotification(String content) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("TransferFile 文件传输服务")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_share)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String content) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(1, buildNotification(content));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "文件传输服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持文件传输服务运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (executor != null) {
            executor.shutdown();
        }
    }
}
