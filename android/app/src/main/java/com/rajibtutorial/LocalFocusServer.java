package com.rajibtutorial;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Tiny local web server (127.0.0.1 only), like the exe's localhost focus link.
 * Chrome (or any browser) can open http://localhost:PORT/focus - the page shows quotes and the live Focus clock.
 * Nothing leaves the phone.
 */
final class LocalFocusServer {
    private LocalFocusServer() { }

    static final int PORT = 38417;
    static final String FOCUS_URL = "http://localhost:" + PORT + "/focus";
    private static Thread th;

    static synchronized void start(final Context c) {
        if (th != null && th.isAlive()) return;
        final Context app = c.getApplicationContext();
        th = new Thread(() -> {
            ServerSocket ss;
            try { ss = new ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1")); }
            catch (Exception e) { return; }
            while (!ss.isClosed()) {
                try {
                    final Socket s = ss.accept();
                    new Thread(() -> handle(app, s), "rt-focus-conn").start();
                } catch (Exception e) {
                    if (ss.isClosed()) break;
                }
            }
        }, "rt-focus-local");
        th.setDaemon(true);
        th.start();
    }

    private static void handle(Context c, Socket s) {
        try {
            s.setSoTimeout(3000);
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), "UTF-8"));
            String line = r.readLine();
            if (line == null) { s.close(); return; }
            String[] p = line.split(" ");
            String path = p.length > 1 ? p[1] : "/";
            String type = "text/html; charset=utf-8", body;
            FocusManager fm = new FocusManager(c);
            if (path.startsWith("/state")) {
                type = "application/json";
                body = "{\"on\":" + fm.isSessionActive() + ",\"ms\":" + fm.getRemainingMs() + "}";
            } else if (path.startsWith("/start")) {
                FocusManager.quickStart(c);
                type = "application/json";
                body = "{\"ok\":true}";
            } else {
                body = PAGE;
            }
            byte[] b = body.getBytes("UTF-8");
            OutputStream o = s.getOutputStream();
            o.write(("HTTP/1.1 200 OK\r\nContent-Type: " + type + "\r\nContent-Length: " + b.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n").getBytes("UTF-8"));
            o.write(b);
            o.flush();
            s.close();
        } catch (Exception e) {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    private static final String PAGE =
        "<!doctype html><html><head><meta charset='utf-8'><title>Focus time</title>"
        + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
        + "<style>body{margin:0;min-height:100vh;background:#0B1F24;color:#fff;font-family:sans-serif;display:flex;align-items:center;justify-content:center;text-align:center}"
        + ".w{padding:26px;max-width:520px}h2{font-size:26px;margin:0 0 10px}.s{color:#B8C7CB;line-height:1.5;font-size:14px}"
        + "q{display:block;font-size:17px;line-height:1.5;margin:20px 0 6px;transition:opacity .4s}small{color:#7FE0A0;font-weight:bold}"
        + ".t{font-size:52px;font-weight:bold;margin:22px 0}button{display:block;width:100%;padding:14px;margin:8px 0;border:0;border-radius:12px;font-size:15px;font-weight:bold;"
        + "background:#1A9C8C;color:#fff}button.g{background:transparent;border:1px solid #3a5a60;color:#B8C7CB}</style></head><body><div class='w'>"
        + "<h2>একটু থামুন 🌿</h2><div class='s'>আপনি যা খুঁজছিলেন তা আপনার লক্ষ্য থেকে মনোযোগ সরিয়ে দিতে পারে। এই মুহূর্তটা আপনার ভবিষ্যৎ গড়ার সময় — চলুন আবার পড়াশোনায় ফিরি।</div>"
        + "<q id='q'></q><small id='a'></small><div class='t' id='t'>25:00</div>"
        + "<button id='b' onclick=\"fetch('/start')\">▶ ২৫ মিনিট Focus শুরু করুন</button>"
        + "<button class='g' onclick='nq()'>💬 আরেকটি উক্তি</button>"
        + "<button class='g' onclick=\"location.href='https://www.google.com/'\">📚 পড়াশোনায় ফিরুন</button></div><script>"
        + "var Q=[['ওঠো, জাগো এবং লক্ষ্যে না পৌঁছানো পর্যন্ত থেমো না।','স্বামী বিবেকানন্দ'],"
        + "['স্বপ্ন সেটা নয় যা তুমি ঘুমিয়ে দেখো, স্বপ্ন সেটাই যা তোমাকে ঘুমাতে দেয় না।','এ. পি. জে. আবদুল কালাম'],"
        + "['এক সময়ে একটি কাজ করো, আর তা করার সময় নিজের সমস্ত মন তাতে ঢেলে দাও।','স্বামী বিবেকানন্দ'],"
        + "['চিত্ত যেথা ভয়শূন্য, উচ্চ যেথা শির।','রবীন্দ্রনাথ ঠাকুর'],['বল বীর — বল উন্নত মম শির!','কাজী নজরুল ইসলাম'],"
        + "['আমরা যা বারবার করি, আমরা তা-ই। তাই শ্রেষ্ঠত্ব কোনো একটি কাজ নয়, এটি একটি অভ্যাস।','অ্যারিস্টটল'],"
        + "['কাজটা শেষ না হওয়া পর্যন্ত সবসময় অসম্ভবই মনে হয়।','নেলসন ম্যান্ডেলা']];"
        + "var i=Math.floor(Math.random()*Q.length);function nq(){i=(i+1)%Q.length;sh()}"
        + "function sh(){document.getElementById('q').textContent='“'+Q[i][0]+'”';document.getElementById('a').textContent='— '+Q[i][1]}sh();setInterval(nq,12000);"
        + "function p(n){return (n<10?'0':'')+n}"
        + "function tick(){fetch('/state').then(function(r){return r.json()}).then(function(d){var s=d.on?Math.ceil(d.ms/1000):1500;"
        + "document.getElementById('t').textContent=p(Math.floor(s/60))+':'+p(s%60);document.getElementById('b').style.display=d.on?'none':'block'}).catch(function(){})}"
        + "tick();setInterval(tick,1000);</script></body></html>";
}
