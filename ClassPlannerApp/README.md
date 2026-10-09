# Class Planner (Android app and widget)

Offline timetable app for Information Science, Year 2, Semester I.

- **App:** shows what is on now and next, a timeline for any day, and the week at a glance.
  Everything is inside the app, so it works with no internet.
- **Widget:** the same "now / next" card on the home screen. Tap it to open the app.
- **Clock:** uses the Ethiopian clock by default (2:00 is 8:00 AM). The button at the top of the app
  switches both the app and the widget to standard time.

## Get the APK

### Option A: GitHub (no Android Studio needed)
1. Create a free account at github.com and make a new repository. Choose **Public** if you want
   to share a download link with friends.
2. Upload everything in this folder, keeping the folder structure. The hidden `.github` folder must
   be included. On a computer, drag the whole folder into "Add file > Upload files".
3. Open the **Actions** tab. "Build APK" starts by itself and takes about 3 to 5 minutes.
4. When it turns green, open the repository's **Releases** page (right side of the main page).
   **ClassPlanner.apk** is there. That page is the link you can send to anyone.

### Option B: Android Studio
Open this folder as a project, wait for the sync, then **Build > Build APK(s)**.
The file is `app/build/outputs/apk/debug/app-debug.apk`.

## Install on a phone
1. Download or receive `ClassPlanner.apk` (Telegram, WhatsApp, USB and Drive all work).
2. Open it. If asked, allow installs from that app. Android may warn about an unknown app
   because it is not from the Play Store. That is expected.
3. To add the widget: touch and hold an empty spot on the home screen, tap **Widgets**,
   choose **Class Planner**.

## Change the timetable
The widget reads `app/src/main/java/com/classplanner/widget/Schedule.java`.
The app reads `app/src/main/assets/index.html` (search for `WEEK` and `COURSES`).
Change both so they match.


## Online mode (optional)
See ONLINE_SETUP.md. Class reps can post cancellations, moves, room changes and announcements; members get them in the app, on the widget and as notifications. Without setup the app stays fully offline.
