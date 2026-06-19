package com.jlxc.vehicleinfoncnn;

import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class RearVisionBridge {
    public interface CommandListener {
        void onCommand(String commandText, InetAddress address, int port);
    }

    private static final String TAG = "RearVisionBridge";
    private final int commandPort;
    private final int mjpegPort;
    private final CommandListener commandListener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object frameLock = new Object();
    private final Object statusLock = new Object();

    private Thread udpThread;
    private Thread httpThread;
    private Thread statusThread;
    private DatagramSocket udpSocket;
    private ServerSocket httpServer;

    private volatile InetAddress controllerAddress;
    private volatile int controllerPort = -1;
    private volatile byte[] latestJpeg;
    private volatile String latestStatus = "{\"type\":\"miku_rear_ai\",\"status\":3}";
    private volatile long streamUntilMs = Long.MAX_VALUE; // 默认一直允许 HTTP 拉流，车机端决定何时显示。
    private volatile long lastCommandMs = 0L;

    public RearVisionBridge(int commandPort, int mjpegPort, CommandListener commandListener) {
        this.commandPort = commandPort;
        this.mjpegPort = mjpegPort;
        this.commandListener = commandListener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        startUdpServer();
        startHttpServer();
        startStatusPusher();
    }

    public void stop() {
        running.set(false);
        try { if (udpSocket != null) udpSocket.close(); } catch (Throwable ignored) { }
        try { if (httpServer != null) httpServer.close(); } catch (Throwable ignored) { }
        try { if (udpThread != null) udpThread.interrupt(); } catch (Throwable ignored) { }
        try { if (httpThread != null) httpThread.interrupt(); } catch (Throwable ignored) { }
        try { if (statusThread != null) statusThread.interrupt(); } catch (Throwable ignored) { }
    }

    public void updateFrame(byte[] jpeg) {
        if (jpeg == null || jpeg.length == 0) return;
        synchronized (frameLock) {
            latestJpeg = jpeg;
        }
    }

    public void updateStatus(String json) {
        if (json == null || json.trim().isEmpty()) return;
        synchronized (statusLock) {
            latestStatus = json;
        }
    }

    public String getLatestStatus() {
        synchronized (statusLock) {
            return latestStatus == null ? "{}" : latestStatus;
        }
    }

    public void enableStreamFor(long durationMs) {
        long now = System.currentTimeMillis();
        if (durationMs <= 0) streamUntilMs = Long.MAX_VALUE;
        else streamUntilMs = now + durationMs;
    }

    public void disableStream() {
        streamUntilMs = 0L;
    }

    public boolean isStreamEnabled() {
        long until = streamUntilMs;
        return until == Long.MAX_VALUE || System.currentTimeMillis() < until;
    }

    private void startUdpServer() {
        udpThread = new Thread(() -> {
            try {
                udpSocket = new DatagramSocket(commandPort);
                udpSocket.setReuseAddress(true);
                byte[] buf = new byte[1024];
                while (running.get()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    if (msg.length() == 0) continue;
                    controllerAddress = packet.getAddress();
                    controllerPort = parseStatusPort(msg, packet.getPort());
                    lastCommandMs = System.currentTimeMillis();
                    if (commandListener != null) commandListener.onCommand(msg, packet.getAddress(), packet.getPort());
                    sendStatusOnce();
                }
            } catch (Throwable t) {
                if (running.get()) Log.w(TAG, "UDP server stopped: " + t.getMessage());
            }
        }, "rear-ai-udp");
        udpThread.setDaemon(true);
        udpThread.start();
    }

    private int parseStatusPort(String msg, int fallback) {
        try {
            String upper = msg.toUpperCase(Locale.US);
            int idx = upper.indexOf("STATUS_PORT=");
            if (idx >= 0) {
                int start = idx + "STATUS_PORT=".length();
                int end = start;
                while (end < msg.length() && Character.isDigit(msg.charAt(end))) end++;
                int p = Integer.parseInt(msg.substring(start, end));
                if (p > 0 && p < 65536) return p;
            }
        } catch (Throwable ignored) { }
        return fallback;
    }

    private void startStatusPusher() {
        statusThread = new Thread(() -> {
            while (running.get()) {
                try {
                    sendStatusOnce();
                    Thread.sleep(250);
                } catch (Throwable ignored) { }
            }
        }, "rear-ai-status");
        statusThread.setDaemon(true);
        statusThread.start();
    }

    private void sendStatusOnce() {
        InetAddress addr = controllerAddress;
        int port = controllerPort;
        if (addr == null || port <= 0) return;
        try {
            byte[] payload = getLatestStatus().getBytes(StandardCharsets.UTF_8);
            DatagramPacket out = new DatagramPacket(payload, payload.length, addr, port);
            DatagramSocket sock = udpSocket;
            if (sock != null && !sock.isClosed()) sock.send(out);
        } catch (Throwable ignored) { }
    }

    private void startHttpServer() {
        httpThread = new Thread(() -> {
            try {
                httpServer = new ServerSocket(mjpegPort);
                while (running.get()) {
                    Socket socket = httpServer.accept();
                    Thread client = new Thread(() -> serveHttpClient(socket), "rear-ai-http-client");
                    client.setDaemon(true);
                    client.start();
                }
            } catch (Throwable t) {
                if (running.get()) Log.w(TAG, "HTTP server stopped: " + t.getMessage());
            }
        }, "rear-ai-http");
        httpThread.setDaemon(true);
        httpThread.start();
    }

    private void serveHttpClient(Socket socket) {
        try {
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(15000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String path = "/";
            String[] parts = requestLine.split(" ");
            if (parts.length >= 2) path = parts[1];
            while (true) {
                String line = reader.readLine();
                if (line == null || line.length() == 0) break;
            }
            if (path.startsWith("/status")) {
                writeJson(socket.getOutputStream(), getLatestStatus());
            } else if (path.startsWith("/snapshot")) {
                byte[] jpg = getLatestJpegOrPlaceholder();
                writeJpeg(socket.getOutputStream(), jpg);
            } else if (path.startsWith("/stream")) {
                writeMjpegStream(socket.getOutputStream());
            } else {
                String body = "Miku Rear AI Node\n/stream\n/status\n/snapshot.jpg\n";
                writeText(socket.getOutputStream(), body);
            }
        } catch (Throwable ignored) {
        } finally {
            try { socket.close(); } catch (Throwable ignored) { }
        }
    }

    private byte[] getLatestJpegOrPlaceholder() {
        synchronized (frameLock) {
            if (latestJpeg != null && latestJpeg.length > 0) return latestJpeg;
        }
        return StreamFrameRenderer.placeholderJpeg(1280, 720, "WAITING CAMERA FRAME");
    }

    private void writeText(OutputStream os, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.flush();
    }

    private void writeJson(OutputStream os, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json; charset=utf-8\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: " + data.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(data);
        os.flush();
    }

    private void writeJpeg(OutputStream os, byte[] jpg) throws IOException {
        os.write(("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        os.write(jpg);
        os.flush();
    }

    private void writeMjpegStream(OutputStream os) throws IOException, InterruptedException {
        String boundary = "miku_rear_ai_frame";
        os.write(("HTTP/1.1 200 OK\r\n" +
                "Content-Type: multipart/x-mixed-replace; boundary=" + boundary + "\r\n" +
                "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
                "Pragma: no-cache\r\n" +
                "Connection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        while (running.get()) {
            byte[] jpg = getLatestJpegOrPlaceholder();
            os.write(("--" + boundary + "\r\nContent-Type: image/jpeg\r\nContent-Length: " + jpg.length + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(jpg);
            os.write("\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            Thread.sleep(isStreamEnabled() ? 90 : 350);
        }
    }
}
