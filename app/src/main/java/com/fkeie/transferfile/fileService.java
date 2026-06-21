package com.fkeie.transferfile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Environment;
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

public class FileTransferService extends Service {
    private static final String CHANNEL_ID = "FileTransferService";
    private static final int PORT = 56789;
    private static final String UPLOAD_DIR = "PoshowFiles";

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private boolean running = false;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newFixedThreadPool(10);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(1, buildNotification());
        startServer();
        return START_STICKY;
    }

    private void startServer() {
        running = true;
        executor.execute(() -> {
            try {
                // 创建上传目录
                File uploadDir = new File(Environment.getExternalStorageDirectory(), UPLOAD_DIR);
                if (!uploadDir.exists()) uploadDir.mkdirs();

                serverSocket = new ServerSocket(PORT);
                String ip = NetworkUtils.getLocalIpAddress();
                updateNotification("服务器运行中 http://" + ip + ":" + PORT);

                while (running && !serverSocket.isClosed()) {
                    Socket client = serverSocket.accept();
                    executor.execute(() -> handleClient(client, uploadDir));
                }

            } catch (IOException e) {
                e.printStackTrace();
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

            // 读取请求头
            StringBuilder headers = new StringBuilder();
            String line;
            int contentLength = 0;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                headers.append(line).append("\n");
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.split(":")[1].trim());
                }
            }

            if (path.equals("/") || path.equals("/index.html")) {
                sendResponse(output, 200, "text/html", getHtmlPage().getBytes());
            } else if (path.equals("/api/files")) {
                handleFileList(output, uploadDir);
            } else if (path.equals("/api/upload") && method.equals("POST")) {
                handleUpload(reader, output, uploadDir, contentLength);
            } else if (path.startsWith("/api/download/")) {
                handleDownload(output, uploadDir, path.substring("/api/download/".length()));
            } else if (path.startsWith("/api/delete/") && method.equals("DELETE")) {
                handleDelete(output, uploadDir, path.substring("/api/delete/".length()));
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
        sendResponse(output, 200, "application/json", json.toString().getBytes());
    }

    private void handleUpload(BufferedReader reader, OutputStream output, File uploadDir, int contentLength) throws IOException {
        // 简化的文件上传处理
        char[] buffer = new char[contentLength];
        reader.read(buffer, 0, contentLength);
        String data = new String(buffer);

        // 解析文件名和内容
        String filename = "uploaded_" + System.currentTimeMillis();
        int nameStart = data.indexOf("filename=\"");
        if (nameStart > 0) {
            nameStart += 10;
            int nameEnd = data.indexOf("\"", nameStart);
            if (nameEnd > nameStart) {
                filename = data.substring(nameStart, nameEnd);
            }
        }

        // 提取文件内容（简化处理，实际应更完善）
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
        File file = new File(uploadDir, new File(filename).getName());
        if (!file.exists() || !file.isFile()) {
            sendResponse(output, 404, "application/json", "{\"error\":\"File not found\"}".getBytes());
            return;
        }

        String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"" + URLEncoder.encode(file.getName(), "UTF-8") + "\"\r\n" +
                "Content-Length: " + file.length() + "\r\n" +
                "Connection: close\r\n\r\n";
        output.write(header.getBytes());

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
        }
    }

    private void handleDelete(OutputStream output, File uploadDir, String filename) throws IOException {
        File file = new File(uploadDir, new File(filename).getName());
        if (file.exists() && file.delete()) {
            sendResponse(output, 200, "application/json", "{\"success\":true}".getBytes());
        } else {
            sendResponse(output, 404, "application/json", "{\"error\":\"File not found\"}".getBytes());
        }
    }

    private void sendResponse(OutputStream output, int status, String contentType, byte[] data) throws IOException {
        String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n" +
                "Content-Type: " + contentType + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n";
        output.write(header.getBytes());
        output.write(data);
    }

    private String getHtmlPage() {
        // 返回简单的Web管理界面
        return "<!DOCTYPE html><html><head>" +
                "<meta charset='UTF-8'><meta name='viewport' content='width=device-width, initial-scale=1.0'>" +
                "<title>Poshow 文件传输</title>" +
                "<style>" +
                "body{font-family:sans-serif;padding:20px;max-width:600px;margin:0 auto;background:#f5f5f5}" +
                ".card{background:white;border-radius:12px;padding:20px;margin-bottom:15px;box-shadow:0 2px 8px rgba(0,0,0,0.1)}" +
                "h1{color:#333;font-size:24px}h2{font-size:18px;color:#666;margin-bottom:15px}" +
                ".upload-area{border:2px dashed #ccc;border-radius:8px;padding:30px;text-align:center;cursor:pointer}" +
                ".upload-area:hover{border-color:#4CAF50;background:#f8fff8}" +
                ".btn{background:#4CAF50;color:white;border:none;padding:10px 20px;border-radius:6px;cursor:pointer}" +
                ".file-item{display:flex;justify-content:space-between;padding:10px;border-bottom:1px solid #eee}" +
                ".file-name{flex:1}.file-size{color:#999;font-size:12px;margin-left:10px}" +
                ".btn-small{padding:5px 10px;font-size:12px;border-radius:4px;border:none;cursor:pointer;margin-left:5px}" +
                ".btn-download{background:#2196F3;color:white}.btn-delete{background:#f44336;color:white}" +
                "</style></head><body>" +
                "<h1>📁 Poshow 文件传输</h1>" +
                "<div class='card'><h2>📤 上传文件</h2>" +
                "<form action='/api/upload' method='post' enctype='multipart/form-data'>" +
                "<input type='file' name='files' multiple><br><br>" +
                "<button type='submit' class='btn'>上传</button></form></div>" +
                "<div class='card'><h2>📥 文件列表</h2><div id='fileList'>加载中...</div></div>" +
                "<script>" +
                "fetch('/api/files').then(r=>r.json()).then(files=>{" +
                "document.getElementById('fileList').innerHTML=files.length?files.map(f=>" +
                "\"<div class='file-item'><div><div class='file-name>\"+f.name+\"</div>\"+" +
                "\"<div class='file-size'>\"+f.size+\" | \"+f.date+\"</div></div>\"+" +
                "\"<div><button class='btn-small btn-download' onclick=\\\"location.href='/api/download/\"+encodeURIComponent(f.name)+\"'\\\">下载</button>\"+" +
                "\"<button class='btn-small btn-delete' onclick=\\\"fetch('/api/delete/\"+encodeURIComponent(f.name)+\"',{method:'DELETE'}).then(()=>location.reload())\\\">删除</button></div></div>\").join('')" +
                ":\"<div style='text-align:center;color:#999;padding:20px'>暂无文件</div>\"})" +
                "</script></body></html>";
    }

    private String formatSize(long bytes) {
        if (bytes <= 0) return "0 B";
        String[] units = {"B", "KB", "MB", "GB"};
        int digitGroups = (int) (Math.log10(bytes) / Math.log10(1024));
        return String.format(Locale.CHINA, "%.1f %s", bytes / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, 
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Poshow 文件传输服务")
            .setContentText("点击管理文件")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Poshow 文件传输服务")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        manager.notify(1, notification);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "文件传输服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持文件传输服务运行");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
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
        executor.shutdown();
    }
}
