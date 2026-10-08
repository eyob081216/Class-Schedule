package com.classplanner.widget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * The full Class Planner, bundled inside the app so it works with no internet.
 * The page lives in assets/index.html and talks to the widget through the "Android" bridge below.
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

    /** Lets the page share its clock setting with the home screen widget. */
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
    }
}
