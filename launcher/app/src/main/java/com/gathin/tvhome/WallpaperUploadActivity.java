package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * "Add wallpapers from your phone": while this screen is open, a tiny web page on the local network takes photos
 * from a phone or laptop, or an image address the TV then downloads itself. Every picture is cropped to 16:9,
 * scaled to 1920x1080 and saved as a JPEG in the app's own {@code eigene/} folder, which theme pushes never clear,
 * and becomes the wallpaper at once. The server only runs while the screen is visible, answers only under a random
 * four-digit path, and stops when the screen closes.
 */
public final class WallpaperUploadActivity extends Activity {
    static final String OWN_DIR = "eigene";
    private static final int PORT = 8765;
    private static final int MAX_BODY = 25 * 1024 * 1024;
    private static final int OUT_W = 1920, OUT_H = 1080;

    private ServerSocket server;
    private Thread serverThread;
    private String token;
    private TextView address;
    private TextView status;
    private TextView count;
    private int port = PORT;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        token = String.format(Locale.ROOT, "%04d", new SecureRandom().nextInt(10000));
        float dp = getResources().getDisplayMetrics().density;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF05070C);
        int pad = Math.round(48 * dp);
        root.setPadding(pad, pad, pad, pad);

        root.addView(text(getString(R.string.upload_title), 30, 0xFFFFFFFF));
        TextView hint = text(getString(R.string.upload_hint), 18, 0xB3FFFFFF);
        hint.setPadding(0, Math.round(16 * dp), 0, Math.round(10 * dp));
        root.addView(hint);
        address = text("", 34, 0xFF8FD3FF);
        root.addView(address);
        status = text(getString(R.string.upload_waiting), 18, 0xE6FFFFFF);
        status.setPadding(0, Math.round(26 * dp), 0, 0);
        root.addView(status);
        count = text("", 16, 0x99FFFFFF);
        count.setPadding(0, Math.round(8 * dp), 0, Math.round(24 * dp));
        root.addView(count);

        TextView clear = new TextView(this, null, 0, R.style.SettingsRow);
        clear.setText(R.string.upload_delete_all);
        clear.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(420 * dp), Math.round(52 * dp));
        clear.setLayoutParams(lp);
        clear.setOnClickListener(v -> confirmDeleteAll());
        root.addView(clear);
        root.addView(text(getString(R.string.upload_back), 15, 0x80FFFFFF));
        setContentView(root);
        updateCount();
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    @Override
    protected void onStart() {
        super.onStart();
        startServer();
    }

    @Override
    protected void onStop() {
        stopServer();
        super.onStop();
    }

    private void startServer() {
        stopServer();
        ServerSocket socket = null;
        for (int p = PORT; p < PORT + 10 && socket == null; p++) {
            try {
                socket = new ServerSocket(p);
                port = p;
            } catch (IOException ignored) {
                // port taken, try the next
            }
        }
        if (socket == null) {
            address.setText(R.string.upload_no_server);
            return;
        }
        server = socket;
        String ip = localAddress();
        address.setText(ip == null ? getString(R.string.upload_no_network)
                : "http://" + ip + ":" + port + "/" + token);
        ServerSocket running = socket;
        serverThread = new Thread(() -> {
            while (!running.isClosed()) {
                try (Socket client = running.accept()) {
                    client.setSoTimeout(60_000);
                    handle(client);
                } catch (IOException ignored) {
                    // closed by stopServer, or a client that went away
                }
            }
        }, "WallpaperUpload");
        serverThread.start();
    }

    private void stopServer() {
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // already closed
            }
            server = null;
        }
        serverThread = null;
    }

    /** The first site-local IPv4 address (Wi-Fi or Ethernet), for the address shown on screen. */
    private static String localAddress() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
            // no network
        }
        return null;
    }

    // ---- HTTP ----

    private void handle(Socket client) throws IOException {
        InputStream in = client.getInputStream();
        OutputStream out = client.getOutputStream();
        String head = readHead(in);
        if (head == null) return;
        String[] lines = head.split("\r\n");
        String[] request = lines[0].split(" ");
        if (request.length < 2) return;
        String method = request[0], path = request[1];
        int length = 0;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try {
                    length = Integer.parseInt(line.substring(colon + 1).trim());
                } catch (NumberFormatException ignored) {
                    length = -1;
                }
            }
        }
        String base = "/" + token;
        if (method.equals("GET") && (path.equals(base) || path.equals(base + "/"))) {
            respond(out, 200, "text/html; charset=utf-8", page());
            return;
        }
        boolean upload = method.equals("POST") && path.equals(base + "/upload");
        boolean link = method.equals("POST") && path.equals(base + "/url");
        if (!upload && !link) {
            respond(out, 404, "text/plain; charset=utf-8", "Nicht gefunden");
            return;
        }
        if (length <= 0 || length > MAX_BODY) {
            respond(out, 413, "text/plain; charset=utf-8", "Datei zu groß (max. 25 MB)");
            return;
        }
        byte[] body = readFully(in, length);
        try {
            byte[] image = upload ? body : download(new String(body, StandardCharsets.UTF_8).trim());
            String name = save(image);
            runOnUiThread(() -> picked(name));
            respond(out, 200, "text/plain; charset=utf-8", "OK " + name);
        } catch (Exception failure) {
            String message = failure.getMessage() == null ? "Fehler" : failure.getMessage();
            runOnUiThread(() -> status.setText(getString(R.string.upload_failed, message)));
            respond(out, 400, "text/plain; charset=utf-8", message);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (head.size() < 16 * 1024) {
            int b = in.read();
            if (b < 0) return null;
            head.write(b);
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return head.toString("ISO-8859-1");
        }
        return null;
    }

    private static byte[] readFully(InputStream in, int length) throws IOException {
        byte[] data = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(data, read, length - read);
            if (n < 0) throw new IOException("Verbindung abgebrochen");
            read += n;
        }
        return data;
    }

    private static void respond(OutputStream out, int code, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String head = "HTTP/1.1 " + code + (code == 200 ? " OK" : " Error") + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + bytes.length + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    /** The TV downloads an image address itself (https, or http), up to 25 MB, following redirects. */
    private static byte[] download(String address) throws IOException {
        if (!address.startsWith("https://") && !address.startsWith("http://")) {
            throw new IOException("Keine gültige Bildadresse (muss mit https:// beginnen)");
        }
        URL url = new URL(address);
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android TV) TvHome");
            connection.setRequestProperty("Accept", "image/*");
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null) break;
                url = new URL(url, next);
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("Download fehlgeschlagen (HTTP " + code + ")");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    data.write(buffer, 0, n);
                    if (data.size() > MAX_BODY) throw new IOException("Bild zu groß (max. 25 MB)");
                }
                return data.toByteArray();
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Zu viele Weiterleitungen");
    }

    /** Crops to 16:9, scales to 1920x1080 and writes a JPEG into eigene/. Returns the file name. */
    private String save(byte[] data) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Das ist kein Bild (JPEG, PNG oder WebP)");
        BitmapFactory.Options options = new BitmapFactory.Options();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= OUT_W && bounds.outHeight / (sample * 2) >= OUT_H) sample *= 2;
        options.inSampleSize = sample;
        Bitmap source = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (source == null) throw new IOException("Bild konnte nicht gelesen werden");
        int w = source.getWidth(), h = source.getHeight();
        Rect crop;
        if (w * OUT_H > h * OUT_W) {
            int cw = h * OUT_W / OUT_H;
            crop = new Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h);
        } else {
            int ch = w * OUT_H / OUT_W;
            crop = new Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch);
        }
        Bitmap out = Bitmap.createBitmap(OUT_W, OUT_H, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(source, crop, new Rect(0, 0, OUT_W, OUT_H), new Paint(Paint.FILTER_BITMAP_FLAG));
        source.recycle();
        File dir = ownDir(this);
        if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
            out.recycle();
            throw new IOException("Speicher nicht verfügbar");
        }
        String name = "eigene-" + System.currentTimeMillis() + ".jpg";
        File file = new File(dir, name);
        try (FileOutputStream stream = new FileOutputStream(file)) {
            out.compress(Bitmap.CompressFormat.JPEG, 88, stream);
        } finally {
            out.recycle();
        }
        return name;
    }

    static File ownDir(android.content.Context context) {
        File base = Config.dir(context);
        return base == null ? null : new File(base, OWN_DIR);
    }

    /** The new picture becomes the wallpaper; Home rebuilds on its next resume (settings revision). */
    private void picked(String name) {
        SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        choices.edit().putString(Config.WALLPAPER_CHOICE_KEY, name)
                .putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1)
                .apply();
        setResult(RESULT_OK);
        status.setText(R.string.upload_done);
        updateCount();
    }

    private void updateCount() {
        List<File> own = WallpaperRotation.images(ownDir(this));
        count.setText(getString(R.string.upload_count, own.size()));
    }

    private void confirmDeleteAll() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.upload_delete_confirm)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    for (File file : WallpaperRotation.images(ownDir(this))) {
                        //noinspection ResultOfMethodCallIgnored
                        file.delete();
                    }
                    SharedPreferences choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
                    String current = choices.getString(Config.WALLPAPER_CHOICE_KEY, "");
                    SharedPreferences.Editor edit = choices.edit();
                    if (current.startsWith("eigene-")) edit.remove(Config.WALLPAPER_CHOICE_KEY);
                    edit.putLong(Config.SETTINGS_REVISION_KEY, choices.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1).apply();
                    setResult(RESULT_OK);
                    updateCount();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The phone page: pick photos (resized in the browser before upload) or paste an image address. */
    private static String page() {
        return "<!doctype html><html lang=de><head><meta charset=utf-8>"
                + "<meta name=viewport content='width=device-width,initial-scale=1'><title>TV-Hintergrund</title>"
                + "<style>body{font-family:system-ui,sans-serif;background:#05070c;color:#eee;margin:0;padding:24px;max-width:560px}"
                + "h1{font-size:22px}label{display:block;margin:22px 0 8px;color:#aab}"
                + "input[type=url]{width:100%;box-sizing:border-box;padding:12px;font-size:16px;border-radius:10px;border:1px solid #334;background:#11151d;color:#fff}"
                + "button,.pick{display:inline-block;margin-top:10px;padding:12px 18px;font-size:16px;border-radius:10px;border:0;background:#2d6cdf;color:#fff}"
                + "#log{margin-top:22px;white-space:pre-line;color:#9fd}</style></head><body>"
                + "<h1>Hintergrund für den TV</h1>"
                + "<label>Fotos vom Handy</label><label class=pick>Fotos auswählen"
                + "<input id=f type=file accept='image/*' multiple hidden></label>"
                + "<label>…oder Bildadresse aus dem Internet</label>"
                + "<input id=u type=url placeholder='https://…/bild.jpg'><button id=b>Herunterladen</button>"
                + "<div id=log></div><script>"
                + "const log=t=>document.getElementById('log').textContent+=t+'\\n';"
                + "const base=location.pathname.replace(/\\/$/,'');"
                + "async function send(path,body){const r=await fetch(base+path,{method:'POST',body});const t=await r.text();"
                + "log((r.ok?'✓ ':'✗ ')+t);}"
                + "function shrink(file){return new Promise((ok,bad)=>{const img=new Image();img.onload=()=>{"
                + "const W=1920,H=1080,s=Math.max(W/img.width,H/img.height),c=document.createElement('canvas');"
                + "c.width=W;c.height=H;c.getContext('2d').drawImage(img,(W-img.width*s)/2,(H-img.height*s)/2,img.width*s,img.height*s);"
                + "c.toBlob(b=>b?ok(b):bad('Bild unlesbar'),'image/jpeg',0.9);URL.revokeObjectURL(img.src)};"
                + "img.onerror=()=>bad('Bild unlesbar');img.src=URL.createObjectURL(file)})}"
                + "document.getElementById('f').onchange=async e=>{for(const f of e.target.files){"
                + "log('Lade '+f.name+' …');try{await send('/upload',await shrink(f))}catch(x){log('✗ '+x)}}e.target.value=''};"
                + "document.getElementById('b').onclick=async()=>{const v=document.getElementById('u').value.trim();"
                + "if(!v)return;log('TV lädt herunter …');try{await send('/url',v)}catch(x){log('✗ '+x)}};"
                + "</script></body></html>";
    }
}
