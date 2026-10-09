package com.classplanner.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The full Class Planner, bundled inside the app so it works with no internet.
 * The page lives in assets/index.html and talks to the phone through the "Android" bridge below.
 * Online features (accounts, class updates) are optional and only switch on after Config.java is filled in.
 */
public class MainActivity extends Activity {
    private static final String KEY_ASKED_WIDGET = "asked_widget";

    private WebView web;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#070C19"));
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        web.addJavascriptInterface(new Bridge(), "Android");

        setContentView(web);
        web.loadUrl("file:///android_asset/index.html");

        if (Cloud.configured() && Cloud.signedIn(this)) UpdateJob.schedule(this);

        // First launch: ask the phone to offer adding the widget to the home screen.
        final SharedPreferences prefs = getSharedPreferences(ClassWidgetProvider.PREFS, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ASKED_WIDGET, false)) {
            prefs.edit().putBoolean(KEY_ASKED_WIDGET, true).apply();
            web.postDelayed(new Runnable() {
                @Override
                public void run() {
                    requestWidget();
                }
            }, 1200);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ClassWidgetProvider.refreshAll(this);
        if (web != null) web.evaluateJavascript("window.onAppResume && window.onAppResume()", null);
    }

    /** Shows the system "Add widget to Home screen?" prompt, unless the widget is already placed. */
    private void requestWidget() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        ComponentName widget = new ComponentName(this, ClassWidgetProvider.class);
        if (manager.getAppWidgetIds(widget).length > 0) return;
        if (manager.isRequestPinAppWidgetSupported()) {
            manager.requestPinAppWidget(widget, null, null);
        }
    }

    /** Android 13 and newer need the person's permission before showing notifications. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    private void joined() {
        UpdateJob.schedule(this);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                askForNotifications();
            }
        });
    }

    private static String fail(String message, int code) {
        try {
            return new JSONObject().put("ok", false).put("error", message).put("code", code).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"Something went wrong.\"}";
        }
    }

    /** Runs one request from the page. Called on a background thread. */
    private String dispatch(String method, String argsJson) throws Exception {
        JSONObject a = new JSONObject(argsJson == null || argsJson.isEmpty() ? "{}" : argsJson);
        Context c = MainActivity.this;
        JSONObject out = new JSONObject().put("ok", true);

        switch (method) {
            case "signUp":
                out.put("session", Cloud.signUp(c, a.optString("name"), a.optString("email"), a.optString("password")));
                break;
            case "signIn": {
                JSONObject s = Cloud.signIn(c, a.optString("email"), a.optString("password"));
                out.put("session", s);
                if (!s.optString("role").isEmpty()) joined();
                break;
            }
            case "setRole":
                out.put("session", Cloud.setRole(c, a.optString("key")));
                joined();
                break;
            case "upgrade":
                out.put("session", Cloud.upgrade(c, a.optString("key")));
                break;
            case "signOut":
                Cloud.signOut(c);
                UpdateJob.cancel(c);
                ClassWidgetProvider.refreshAll(c);
                out.put("session", Cloud.sessionJson(c));
                break;
            case "choice":
                Cloud.setChoice(c, a.optString("value"));
                break;
            case "fetch": {
                JSONArray all = Cloud.fetchUpdates(c);
                Cloud.takeNew(c, all, false);
                out.put("updates", all);
                ClassWidgetProvider.refreshAll(c);
                break;
            }
            case "post": {
                Cloud.postUpdate(c, a);
                JSONArray all = Cloud.fetchUpdates(c);
                Cloud.takeNew(c, all, false);
                out.put("updates", all);
                ClassWidgetProvider.refreshAll(c);
                break;
            }
            case "delete": {
                Cloud.deleteUpdate(c, a.optString("id"));
                JSONArray all = Cloud.fetchUpdates(c);
                Cloud.takeNew(c, all, false);
                out.put("updates", all);
                ClassWidgetProvider.refreshAll(c);
                break;
            }
            default:
                throw new Cloud.CloudException("Unknown request.", 0);
        }
        return out.toString();
    }

    /** What the page can ask the phone to do. */
    private class Bridge {
        @JavascriptInterface
        public boolean isEthiopian() {
            SharedPreferences prefs = getSharedPreferences(ClassWidgetProvider.PREFS, Context.MODE_PRIVATE);
            return prefs.getBoolean(ClassWidgetProvider.KEY_ETHIOPIAN, true);
        }

        @JavascriptInterface
        public void setEthiopian(boolean ethiopian) {
            SharedPreferences prefs = getSharedPreferences(ClassWidgetProvider.PREFS, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(ClassWidgetProvider.KEY_ETHIOPIAN, ethiopian).apply();
            ClassWidgetProvider.refreshAll(MainActivity.this);
        }

        @JavascriptInterface
        public boolean isNight() {
            int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
            return mode == Configuration.UI_MODE_NIGHT_YES;
        }

        @JavascriptInterface
        public void addWidget() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    requestWidget();
                }
            });
        }

        @JavascriptInterface
        public String session() {
            return Cloud.sessionJson(MainActivity.this).toString();
        }

        @JavascriptInterface
        public String updates() {
            return Cloud.cachedUpdatesJson(MainActivity.this);
        }

        /** Online requests. The answer comes back through window.__cb(cbId, jsonText). */
        @JavascriptInterface
        public void call(final String method, final String args, final int cbId) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    String result;
                    try {
                        result = dispatch(method, args);
                    } catch (Cloud.CloudException e) {
                        result = fail(e.getMessage(), e.code);
                    } catch (Exception e) {
                        result = fail("Something went wrong. " + e.getMessage(), 0);
                    }
                    final String js = "window.__cb(" + cbId + "," + JSONObject.quote(result) + ")";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            web.evaluateJavascript(js, null);
                        }
                    });
                }
            }).start();
        }
    }
}
