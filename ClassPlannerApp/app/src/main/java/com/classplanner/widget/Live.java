package com.classplanner.widget;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps one open connection to the Realtime Database "ping" spot. When the rep posts something, the
 * database pushes a message down this connection within a second, and we go and fetch the update.
 */
final class Live {
    interface Ping {
        void onPing();
    }

    private Live() {}

    /** Runs until stop is set. Reconnects by itself after any failure. */
    static void listen(Context c, AtomicBoolean stop, Ping ping) {
        long wait = 2000;
        while (!stop.get()) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(Cloud.RTDB_BASE + "/ping.json").openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(75000); // the database sends a keep-alive about every 30 seconds
                conn.setRequestProperty("Accept", "text/event-stream");
                if (conn.getResponseCode() != 200) throw new java.io.IOException("HTTP " + conn.getResponseCode());
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                wait = 2000;
                String event = "";
                String line;
                while (!stop.get() && (line = in.readLine()) != null) {
                    if (line.startsWith("event:")) {
                        event = line.substring(6).trim();
                    } else if (line.startsWith("data:")) {
                        // The first "put" is the current value, which also catches anything missed while offline.
                        if (event.equals("put") || event.equals("patch")) ping.onPing();
                    } else if (line.isEmpty()) {
                        event = "";
                    }
                }
            } catch (Exception e) {
                // Offline, timed out or the server hung up: wait a little, then connect again.
            } finally {
                if (conn != null) conn.disconnect();
            }
            if (stop.get()) return;
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 60000);
        }
    }
}
