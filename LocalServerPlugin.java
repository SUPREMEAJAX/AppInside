package com.appinside.app;

import android.util.Base64;
import android.view.WindowManager;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tiny HTTP server for the AppInside master device.
 * Each incoming request is handed to the web app (event "request"); the web app
 * answers with respond({id, status, contentType, body | bodyBase64}).
 */
@CapacitorPlugin(name = "LocalServer")
public class LocalServerPlugin extends Plugin {
    private ServerSocket server;
    private ExecutorService pool;
    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();
    private static final int MAX_BODY = 10 * 1024 * 1024;

    private static class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile JSObject res;
    }

    @PluginMethod
    public void start(PluginCall call) {
        int port = call.getInt("port", 8787);
        stopServer();
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(port));
            server = s;
            pool = Executors.newCachedThreadPool();
            Thread t = new Thread(() -> acceptLoop(s), "AppInsideServer");
            t.setDaemon(true);
            t.start();
            JSObject r = new JSObject();
            r.put("port", port);
            r.put("addresses", addressList());
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Could not start server: " + e.getMessage());
        }
    }

    @PluginMethod
    public void stop(PluginCall call) {
        stopServer();
        call.resolve();
    }

    @PluginMethod
    public void addresses(PluginCall call) {
        JSObject r = new JSObject();
        r.put("addresses", addressList());
        r.put("running", server != null && !server.isClosed());
        call.resolve(r);
    }

    @PluginMethod
    public void respond(PluginCall call) {
        String id = call.getString("id");
        if (id != null) {
            Pending p = pending.get(id);
            if (p != null) {
                p.res = call.getData();
                p.latch.countDown();
            }
        }
        call.resolve();
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        final boolean on = Boolean.TRUE.equals(call.getBoolean("on", true));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        stopServer();
    }

    private void stopServer() {
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        server = null;
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private JSArray addressList() {
        JSArray a = new JSArray();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress ad : Collections.list(ni.getInetAddresses())) {
                    if (ad instanceof Inet4Address) a.put(ad.getHostAddress());
                }
            }
        } catch (Exception ignored) {
        }
        return a;
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                final Socket c = s.accept();
                ExecutorService p = pool;
                if (p != null) p.submit(() -> handle(c));
                else c.close();
            } catch (Exception e) {
                if (s.isClosed()) break;
            }
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') break;
            if (ch != '\r') b.write(ch);
            if (b.size() > 16384) throw new IOException("Line too long");
        }
        if (ch == -1 && b.size() == 0) return null;
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private void handle(Socket c) {
        String id = UUID.randomUUID().toString();
        try (Socket sock = c) {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            String first = readLine(in);
            if (first == null) return;
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            JSObject headers = new JSObject();
            int len = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int i = line.indexOf(':');
                if (i > 0) {
                    String k = line.substring(0, i).trim().toLowerCase(Locale.ROOT);
                    String v = line.substring(i + 1).trim();
                    headers.put(k, v);
                    if (k.equals("content-length")) {
                        try { len = Integer.parseInt(v); } catch (NumberFormatException e) { len = -1; }
                    }
                }
            }
            if (len < 0 || len > MAX_BODY) {
                write(sock, 413, "application/json", "{\"error\":\"Request too large\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            byte[] body = new byte[len];
            int off = 0;
            while (off < len) {
                int r = in.read(body, off, len - off);
                if (r < 0) break;
                off += r;
            }
            JSObject req = new JSObject();
            req.put("id", id);
            req.put("method", parts[0]);
            req.put("path", parts[1]);
            req.put("headers", headers);
            req.put("body", new String(body, 0, off, StandardCharsets.UTF_8));
            req.put("remote", sock.getInetAddress().getHostAddress());
            Pending p = new Pending();
            pending.put(id, p);
            notifyListeners("request", req);
            boolean ok = p.latch.await(20, TimeUnit.SECONDS);
            JSObject res = p.res;
            if (!ok || res == null) {
                write(sock, 503, "application/json", "{\"error\":\"Master is busy or AppInside is in the background\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            int status = res.getInteger("status", 200);
            String type = res.getString("contentType", "application/json");
            String b64 = res.getString("bodyBase64", null);
            byte[] out = (b64 != null && !b64.isEmpty())
                    ? Base64.decode(b64, Base64.DEFAULT)
                    : res.getString("body", "").getBytes(StandardCharsets.UTF_8);
            write(sock, status, type, out);
        } catch (Exception ignored) {
        } finally {
            pending.remove(id);
        }
    }

    private static void write(Socket s, int status, String type, byte[] body) throws IOException {
        String reason = status == 200 ? "OK" : status == 204 ? "No Content" : status == 404 ? "Not Found" : "Status";
        String head = "HTTP/1.1 " + status + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: Authorization, Content-Type\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        OutputStream o = s.getOutputStream();
        o.write(head.getBytes(StandardCharsets.UTF_8));
        o.write(body);
        o.flush();
    }
}
