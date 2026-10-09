# Turn on online mode (about 10 minutes, free)

Until you do this, the app works exactly like the offline version. Nothing breaks.

## 1. Make the Firebase project
1. Go to https://console.firebase.google.com and sign in with a Google account.
2. Click **Create a project**. Name it `class-planner`. Turn Google Analytics **off**. Click Create.

## 2. Turn on sign-in
1. Left menu: **Build > Authentication > Get started**.
2. Click **Email/Password**, switch it **on**, click Save.

## 3. Make the database
1. Left menu: **Build > Firestore Database > Create database**.
2. Pick any location near you. Choose **Production mode**. Click Create.

## 4. Paste the rules
1. In Firestore, open the **Rules** tab.
2. Delete everything there. Paste the whole contents of `firestore.rules` from this project.
3. Click **Publish**.

## 5. Set the rep key
1. In Firestore, open the **Data** tab. Click **Start collection**.
2. Collection ID: `config`. Click Next.
3. Document ID: `rep`.
4. Field name: `key`. Type: **string**. Value: your rep key (you picked 1992; a longer one is safer, for example 8 digits).
5. Click Save.

To change the key later, edit that field. No new app needed.

## 6. Copy two values into the app
1. Click the gear icon, **Project settings**.
2. Copy **Project ID** (General tab).
3. Copy **Web API Key** (same page).
4. On GitHub, open `app/src/main/java/com/classplanner/widget/Config.java`, click the pencil, replace `YOUR_PROJECT_ID` and `YOUR_WEB_API_KEY` with your two values (keep the quotes), and Commit.
5. The Actions tab builds a new APK. Download it from Releases and install it.

## How it works for people
- First launch: sign in or create an account. A new account sees "One more step": enter the rep key to become the rep, or tap **Skip**.
- Rep: tap your name at the top > **Post an update** (cancel, move, change room or instructor, announcement).
- Everyone: updates show in the app, on the day view and on the widget. Phones check about every 15 minutes and show a notification.

## Good to know
- Notifications are not instant. They arrive within about 15 minutes, sometimes later with battery saver.
- Anyone can read the posted updates. Only the rep can post or remove them.
- Free Firebase limits are far above what one class uses.
