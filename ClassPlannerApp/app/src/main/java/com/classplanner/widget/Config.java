package com.classplanner.widget;

/**
 * Online mode settings from the Firebase project (see ONLINE_SETUP.md).
 * While they still say YOUR_..., that part of the app stays off.
 */
final class Config {
    static final String PROJECT_ID = "class-planner-81be5";
    static final String API_KEY = "AIzaSyDvvUi7HpizGci6ZIsCaGfEC0EYAdDfL_w";
    /** Realtime Database address, for instant notifications (see ONLINE_SETUP.md, step 7). */
    static final String DATABASE_URL = "https://class-planner-81be5-default-rtdb.firebaseio.com";

    private Config() {}
}
