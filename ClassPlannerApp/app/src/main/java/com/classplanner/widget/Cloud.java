package com.classplanner.widget;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The online side of Class Planner: Firebase sign-in and class updates, over plain web requests
 * (no Firebase library). Every method that touches the network must run off the main thread.
 */
final class Cloud {
    // Overridable so tests can point at a local mock server.
    static String AUTH_BASE = "https://identitytoolkit.googleapis.com";
    static String TOKEN_BASE = "https://securetoken.googleapis.com";
    static String DB_BASE = "https://firestore.googleapis.com";

    private static final String PREFS = "class_planner";

    private Cloud() {}

    static final class CloudException extends Exception {
        final int code;

        CloudException(String message, int code) {
            super(message);
            this.code = code;
        }
    }

    private static final class Resp {
        final int code;
        final String body;

        Resp(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    // ------------------------------------------------------------------ session

    static boolean configured() {
        return !Config.PROJECT_ID.startsWith("YOUR_") && !Config.API_KEY.startsWith("YOUR_");
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context c) {
        return !prefs(c).getString("refreshToken", "").isEmpty();
    }

    static JSONObject sessionJson(Context c) {
        SharedPreferences p = prefs(c);
        try {
            return new JSONObject()
                    .put("configured", configured())
                    .put("signedIn", signedIn(c))
                    .put("uid", p.getString("uid", ""))
                    .put("email", p.getString("email", ""))
                    .put("name", p.getString("name", ""))
                    .put("role", p.getString("role", ""))
                    .put("choice", p.getString("choice", ""));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    static void setChoice(Context c, String value) {
        prefs(c).edit().putString("choice", value).apply();
    }

    static void signOut(Context c) {
        clearSession(c);
    }

    private static void clearSession(Context c) {
        prefs(c).edit()
                .remove("uid").remove("email").remove("name").remove("role")
                .remove("idToken").remove("refreshToken").remove("expiresAt")
                .remove("seen").remove("seenInit").remove("updates")
                .apply();
    }

    private static void requireConfigured() throws CloudException {
        if (!configured()) {
            throw new CloudException("Online mode is not set up yet. See ONLINE_SETUP.md.", 0);
        }
    }

    // ------------------------------------------------------------------ accounts

    static JSONObject signUp(Context c, String name, String email, String password) throws CloudException {
        requireConfigured();
        name = name.trim();
        email = email.trim();
        if (name.isEmpty()) throw new CloudException("Please enter your name.", 0);
        JSONObject auth = authCall("accounts:signUp", email, password);
        saveSession(c, auth, email, name, "");
        return sessionJson(c);
    }

    static JSONObject signIn(Context c, String email, String password) throws CloudException {
        requireConfigured();
        email = email.trim();
        JSONObject auth = authCall("accounts:signInWithPassword", email, password);
        int at = email.indexOf('@');
        saveSession(c, auth, email, at > 0 ? email.substring(0, at) : email, "");
        try {
            loadProfile(c);
        } catch (CloudException e) {
            clearSession(c);
            throw e;
        }
        return sessionJson(c);
    }

    /** Creates the profile. An empty key joins as a member; a key asks the server to make you a rep. */
    static JSONObject setRole(Context c, String repKey) throws CloudException {
        SharedPreferences p = prefs(c);
        String uid = p.getString("uid", "");
        if (uid.isEmpty()) throw new CloudException("Please sign in first.", 401);
        repKey = repKey.trim();
        boolean rep = !repKey.isEmpty();
        try {
            JSONObject fields = new JSONObject()
                    .put("name", sv(p.getString("name", "")))
                    .put("role", sv(rep ? "rep" : "member"));
            if (rep) fields.put("repKey", sv(repKey));
            JSONObject doc = new JSONObject().put("fields", fields);
            Resp r = authed(c, "POST", docsUrl() + "/users?documentId=" + enc(uid), doc.toString());
            if (r.code == 200) {
                p.edit().putString("role", rep ? "rep" : "member").apply();
                return sessionJson(c);
            }
            if (r.code == 409) { // profile already exists
                loadProfile(c);
                return sessionJson(c);
            }
            if (r.code == 403 && rep) throw new CloudException("That rep key is not correct.", 403);
            throw dbError(r);
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    /** A member who later gets the key can become a rep. */
    static JSONObject upgrade(Context c, String repKey) throws CloudException {
        SharedPreferences p = prefs(c);
        String uid = p.getString("uid", "");
        repKey = repKey.trim();
        if (uid.isEmpty()) throw new CloudException("Please sign in first.", 401);
        if (repKey.isEmpty()) throw new CloudException("Enter the rep key.", 0);
        try {
            JSONObject update = new JSONObject()
                    .put("name", "projects/" + Config.PROJECT_ID + "/databases/(default)/documents/users/" + uid)
                    .put("fields", new JSONObject().put("role", sv("rep")).put("repKey", sv(repKey)));
            JSONObject write = new JSONObject()
                    .put("update", update)
                    .put("updateMask", new JSONObject().put("fieldPaths", new JSONArray().put("role").put("repKey")));
            JSONObject body = new JSONObject().put("writes", new JSONArray().put(write));
            Resp r = authed(c, "POST", dbRoot() + "/documents:commit", body.toString());
            if (r.code == 200) {
                p.edit().putString("role", "rep").apply();
                return sessionJson(c);
            }
            if (r.code == 403) throw new CloudException("That rep key is not correct.", 403);
            throw dbError(r);
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    private static JSONObject authCall(String path, String email, String password) throws CloudException {
        try {
            JSONObject body = new JSONObject()
                    .put("email", email)
                    .put("password", password)
                    .put("returnSecureToken", true);
            Resp r = http("POST", AUTH_BASE + "/v1/" + path + "?key=" + enc(Config.API_KEY),
                    null, body.toString(), "application/json");
            if (r.code != 200) throw authError(r);
            return new JSONObject(r.body);
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    private static void saveSession(Context c, JSONObject auth, String email, String name, String role) {
        long ttl = parseLong(auth.optString("expiresIn", "3600"), 3600);
        prefs(c).edit()
                .putString("uid", auth.optString("localId", ""))
                .putString("email", email)
                .putString("name", name)
                .putString("role", role)
                .putString("idToken", auth.optString("idToken", ""))
                .putString("refreshToken", auth.optString("refreshToken", ""))
                .putLong("expiresAt", System.currentTimeMillis() + ttl * 1000L)
                .putString("seen", "")
                .putBoolean("seenInit", false)
                .apply();
    }

    private static void loadProfile(Context c) throws CloudException {
        SharedPreferences p = prefs(c);
        Resp r = authed(c, "GET", docsUrl() + "/users/" + enc(p.getString("uid", "")), null);
        if (r.code == 404) return; // no profile yet: the app asks for the optional rep key
        if (r.code != 200) throw dbError(r);
        try {
            JSONObject f = new JSONObject(r.body).optJSONObject("fields");
            if (f == null) return;
            String role = strField(f, "role");
            String name = strField(f, "name");
            SharedPreferences.Editor e = p.edit();
            e.putString("role", role);
            if (!name.isEmpty()) e.putString("name", name);
            e.apply();
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    private static String ensureToken(Context c) throws CloudException {
        SharedPreferences p = prefs(c);
        String token = p.getString("idToken", "");
        long exp = p.getLong("expiresAt", 0);
        if (!token.isEmpty() && System.currentTimeMillis() < exp - 60_000L) return token;

        String refresh = p.getString("refreshToken", "");
        if (refresh.isEmpty()) throw new CloudException("Please sign in first.", 401);
        Resp r = http("POST", TOKEN_BASE + "/v1/token?key=" + enc(Config.API_KEY), null,
                "grant_type=refresh_token&refresh_token=" + enc(refresh), "application/x-www-form-urlencoded");
        if (r.code != 200) throw new CloudException("Your sign-in expired. Please sign in again.", 401);
        try {
            JSONObject j = new JSONObject(r.body);
            String id = j.optString("id_token", "");
            if (id.isEmpty()) throw new CloudException("Your sign-in expired. Please sign in again.", 401);
            long ttl = parseLong(j.optString("expires_in", "3600"), 3600);
            p.edit().putString("idToken", id)
                    .putString("refreshToken", j.optString("refresh_token", refresh))
                    .putLong("expiresAt", System.currentTimeMillis() + ttl * 1000L)
                    .apply();
            return id;
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    private static Resp authed(Context c, String method, String url, String body) throws CloudException {
        String token = ensureToken(c);
        return http(method, url, token, body, "application/json");
    }

    // ------------------------------------------------------------------ class updates

    /** Reads the latest updates (anyone can read) and keeps a copy for offline use and the widget. */
    static JSONArray fetchUpdates(Context c) throws CloudException {
        requireConfigured();
        String base = docsUrl() + "/updates?pageSize=100&key=" + enc(Config.API_KEY);
        Resp r = http("GET", base + "&orderBy=createdAt%20desc", null, null, null);
        if (r.code == 400) r = http("GET", base, null, null, null);
        if (r.code != 200) throw dbError(r);

        List<JSONObject> items = new ArrayList<>();
        try {
            JSONArray docs = new JSONObject(r.body).optJSONArray("documents");
            if (docs != null) {
                for (int i = 0; i < docs.length(); i++) {
                    JSONObject d = docs.optJSONObject(i);
                    if (d == null) continue;
                    JSONObject f = d.optJSONObject("fields");
                    if (f == null) continue;
                    String name = d.optString("name", "");
                    JSONObject o = new JSONObject()
                            .put("id", name.substring(name.lastIndexOf('/') + 1))
                            .put("type", strField(f, "type"))
                            .put("course", strField(f, "course"))
                            .put("date", strField(f, "date"))
                            .put("toDate", strField(f, "toDate"))
                            .put("toPeriod", longField(f, "toPeriod"))
                            .put("room", strField(f, "room"))
                            .put("who", strField(f, "who"))
                            .put("text", strField(f, "text"))
                            .put("by", strField(f, "by"))
                            .put("createdAt", longField(f, "createdAt"));
                    items.add(o);
                }
            }
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
        Collections.sort(items, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("createdAt", 0), a.optLong("createdAt", 0));
            }
        });
        JSONArray out = new JSONArray();
        for (JSONObject o : items) out.put(o);
        prefs(c).edit().putString("updates", out.toString()).apply();
        return out;
    }

    /** Posts an update. The server only accepts it from a rep account. Returns the new update id. */
    static String postUpdate(Context c, JSONObject u) throws CloudException {
        String type = u.optString("type", "");
        if (!(type.equals("cancel") || type.equals("move") || type.equals("room") || type.equals("announce"))) {
            throw new CloudException("Pick what kind of update this is.", 0);
        }
        String by = prefs(c).getString("name", "");
        String text = clip(u.optString("text", ""), 500);
        try {
            JSONObject f = new JSONObject();
            f.put("type", sv(type));
            if (type.equals("announce")) {
                if (text.isEmpty()) throw new CloudException("Write the announcement first.", 0);
            } else {
                String course = u.optString("course", "");
                if (Schedule.course(course) == null) throw new CloudException("Pick a class.", 0);
                LocalDate date = parseDate(u.optString("date", ""));
                if (date == null) throw new CloudException("Pick the date of the class.", 0);
                f.put("course", sv(course)).put("date", sv(date.toString()));
                if (type.equals("move")) {
                    LocalDate to = parseDate(u.optString("toDate", ""));
                    int period = u.optInt("toPeriod", 0);
                    if (to == null || period < 1 || period > 8) {
                        throw new CloudException("Pick the new date and time.", 0);
                    }
                    f.put("toDate", sv(to.toString())).put("toPeriod", iv(period));
                }
                if (type.equals("room")) {
                    String room = clip(u.optString("room", ""), 60);
                    String who = clip(u.optString("who", ""), 60);
                    if (room.isEmpty() && who.isEmpty()) {
                        throw new CloudException("Enter the new room or instructor.", 0);
                    }
                    if (!room.isEmpty()) f.put("room", sv(room));
                    if (!who.isEmpty()) f.put("who", sv(who));
                }
            }
            if (!text.isEmpty()) f.put("text", sv(text));
            f.put("by", sv(by));
            f.put("createdAt", iv(System.currentTimeMillis()));

            Resp r = authed(c, "POST", docsUrl() + "/updates", new JSONObject().put("fields", f).toString());
            if (r.code == 403) {
                throw new CloudException("Only the class rep can post updates. Sign in with a rep account.", 403);
            }
            if (r.code != 200) throw dbError(r);
            String name = new JSONObject(r.body).optString("name", "");
            return name.substring(name.lastIndexOf('/') + 1);
        } catch (JSONException e) {
            throw new CloudException("Unexpected reply from the server.", 0);
        }
    }

    static void deleteUpdate(Context c, String id) throws CloudException {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new CloudException("Unknown update.", 0);
        Resp r = authed(c, "DELETE", docsUrl() + "/updates/" + id, null);
        if (r.code == 403) throw new CloudException("Only the class rep can remove updates.", 403);
        if (r.code != 200) throw dbError(r);
    }

    // ------------------------------------------------------------------ notifications bookkeeping

    /**
     * Remembers which updates the person has seen. With notify = true it returns the updates that are
     * new since the last check (nothing on the very first check after signing in). With notify = false
     * everything is simply marked as seen, which is what happens while the app is open.
     */
    static JSONArray takeNew(Context c, JSONArray all, boolean notify) {
        SharedPreferences p = prefs(c);
        Set<String> seen = new HashSet<>();
        for (String s : p.getString("seen", "").split(",")) {
            if (!s.isEmpty()) seen.add(s);
        }
        boolean init = p.getBoolean("seenInit", false);
        JSONArray fresh = new JSONArray();
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < all.length(); i++) {
            JSONObject o = all.optJSONObject(i);
            if (o == null) continue;
            String id = o.optString("id", "");
            if (id.isEmpty()) continue;
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
            if (init && notify && !seen.contains(id)) fresh.put(o);
        }
        p.edit().putString("seen", ids.toString()).putBoolean("seenInit", true).apply();
        return fresh;
    }

    /** The last downloaded updates, as schedule changes, for the widget and notifications. */
    static List<Schedule.Change> cachedChanges(Context c) {
        try {
            return parseChanges(new JSONArray(prefs(c).getString("updates", "[]")));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    static String cachedUpdatesJson(Context c) {
        return prefs(c).getString("updates", "[]");
    }

    static List<Schedule.Change> parseChanges(JSONArray arr) {
        List<Schedule.Change> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            Schedule.Change ch = new Schedule.Change();
            ch.id = o.optString("id", "");
            ch.type = o.optString("type", "");
            ch.course = o.optString("course", "");
            ch.room = o.optString("room", "");
            ch.who = o.optString("who", "");
            ch.text = o.optString("text", "");
            ch.by = o.optString("by", "");
            ch.date = parseDate(o.optString("date", ""));
            ch.toDate = parseDate(o.optString("toDate", ""));
            ch.toPeriod = o.optInt("toPeriod", 0);
            ch.createdAt = o.optLong("createdAt", 0);
            out.add(ch);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    private static String dbRoot() {
        return DB_BASE + "/v1/projects/" + Config.PROJECT_ID + "/databases/(default)";
    }

    private static String docsUrl() {
        return dbRoot() + "/documents";
    }

    private static JSONObject sv(String s) throws JSONException {
        return new JSONObject().put("stringValue", s);
    }

    private static JSONObject iv(long n) throws JSONException {
        return new JSONObject().put("integerValue", String.valueOf(n));
    }

    private static String strField(JSONObject fields, String key) {
        JSONObject v = fields.optJSONObject(key);
        return v == null ? "" : v.optString("stringValue", "");
    }

    private static long longField(JSONObject fields, String key) {
        JSONObject v = fields.optJSONObject(key);
        if (v == null) return 0;
        String s = v.optString("integerValue", "");
        return s.isEmpty() ? 0 : parseLong(s, 0);
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static LocalDate parseDate(String s) {
        try {
            return s == null || s.isEmpty() ? null : LocalDate.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        s = s.trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    private static String errorMessage(Resp r) {
        try {
            return new JSONObject(r.body).optJSONObject("error").optString("message", "");
        } catch (Exception e) {
            return "";
        }
    }

    private static CloudException authError(Resp r) {
        String msg = errorMessage(r);
        String m = msg.toUpperCase(Locale.ROOT);
        if (m.startsWith("EMAIL_EXISTS")) return new CloudException("That email already has an account. Try signing in.", r.code);
        if (m.startsWith("INVALID_PASSWORD") || m.startsWith("INVALID_LOGIN_CREDENTIALS") || m.startsWith("EMAIL_NOT_FOUND")) {
            return new CloudException("Wrong email or password.", r.code);
        }
        if (m.startsWith("WEAK_PASSWORD")) return new CloudException("Use a password with at least 6 characters.", r.code);
        if (m.startsWith("INVALID_EMAIL") || m.startsWith("MISSING_EMAIL")) return new CloudException("That email address does not look right.", r.code);
        if (m.startsWith("MISSING_PASSWORD")) return new CloudException("Please enter a password.", r.code);
        if (m.startsWith("TOO_MANY_ATTEMPTS")) return new CloudException("Too many tries. Wait a few minutes and try again.", r.code);
        if (m.startsWith("OPERATION_NOT_ALLOWED")) return new CloudException("Email sign-in is not turned on in Firebase yet. See ONLINE_SETUP.md.", r.code);
        if (m.contains("API KEY")) return new CloudException("The Firebase key in Config.java is wrong. See ONLINE_SETUP.md.", r.code);
        return new CloudException("Could not sign in (" + r.code + "). " + msg, r.code);
    }

    private static CloudException dbError(Resp r) {
        String msg = errorMessage(r);
        if (r.code == 401) return new CloudException("Your sign-in expired. Please sign in again.", 401);
        if (r.code == 403) return new CloudException("The server refused this. Check the Firebase security rules in ONLINE_SETUP.md.", 403);
        if (r.code == 404) return new CloudException("Firebase database not found. Check the project ID and that Firestore is created.", 404);
        return new CloudException("Server error (" + r.code + "). " + msg, r.code);
    }

    private static Resp http(String method, String url, String bearer, String body, String contentType)
            throws CloudException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod(method);
            conn.setRequestProperty("Accept", "application/json");
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer " + bearer);
            if (body != null) {
                byte[] data = body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", contentType);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(data);
                }
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            return new Resp(code, in == null ? "" : readAll(in));
        } catch (IOException e) {
            throw new CloudException("No internet connection. Try again when you are online.", 0);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }
}
