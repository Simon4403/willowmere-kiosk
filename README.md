# Willowmere Kiosk

Android app that locks a tablet to the Willowmere website. Staff can only use Willowmere; only someone with the admin PIN can reach Wi-Fi, Android settings, or leave the kiosk.

## What it does

**Kiosk**
- Full-screen browser on the Willowmere address, with the system bars hidden.
- Navigation is limited to the Willowmere site plus any extra sites you allow; everything else is blocked.
- Back only moves back inside Willowmere. Downloads, pop-up windows, `tel:`/`mailto:` links, camera, microphone and location are all blocked.
- Shows a "Can't reach Willowmere" screen when offline and retries every 15 seconds, plus straight away when Wi-Fi returns.
- Restarts the browser engine by itself if it crashes.
- Keeps the screen on (optional) and can return to the start page after idle time (optional, off by default).
- File uploads (photos, documents) work; the file picker is let in briefly, then locked out again.
- Comes back by itself after a restart or an app update.

**With device owner (QR set-up)**
- Lock task mode: no home, recents, notifications or power menu.
- Status bar, safe mode, factory reset from settings, USB file transfer and screenshots are blocked; USB debugging is blocked optionally.
- The lock screen is turned off, because Willowmere has its own login.
- Android system updates install between 02:00 and 05:00.

**Admin menu**
- Open it by tapping the **top-left corner 7 times quickly**, then entering the PIN.
- 5 wrong PINs lock the prompt for 5 minutes. The menu closes itself after 3 minutes untouched.
- Wi-Fi settings and all Android settings. These snap back to the kiosk after 5 minutes.
- Change the start address and allowed sites, reload, clear browsing data, change the PIN.
- Install an app update from a link (silent as device owner).
- Exit kiosk for 30 minutes. It relocks automatically, on restart, or when you open the app.
- Remove kiosk control, ready to repurpose or factory reset the tablet.

## One-time setup

### 1. Repository

Create a **public** GitHub repo called `willowmere-kiosk`.

It must be public because tablets download the APK from the repo's Releases page during set-up. Nothing secret lives in the code: the signing key goes in GitHub Secrets. If you'd rather keep the repo private, host the APK somewhere public (for example Azure Blob Storage) and set the repository variable `KIOSK_APK_URL` to that link.

Upload these files (see `UPLOAD-ME.txt`).

### 2. Signing key and settings

Run these once, in a Codespace on the repo:

```bash
# gh needs your own login to write secrets (the Codespace token can't)
unset GITHUB_TOKEN && gh auth login

# Create the signing key
PW=$(openssl rand -base64 24 | tr -dc 'A-Za-z0-9')
keytool -genkeypair -keystore kiosk-release.jks -storetype PKCS12 -alias kiosk \
  -keyalg RSA -keysize 4096 -validity 10000 -storepass "$PW" -keypass "$PW" \
  -dname "CN=Hera Care Solutions Ltd, O=Hera Care Solutions Ltd, C=GB"

# Store it as repo secrets, and set your Willowmere address
base64 -w0 kiosk-release.jks | gh secret set KIOSK_KEYSTORE_B64
gh secret set KIOSK_KEYSTORE_PASSWORD --body "$PW"
gh secret set KIOSK_KEY_ALIAS --body "kiosk"
gh variable set KIOSK_START_URL --body "https://YOUR-WILLOWMERE-ADDRESS"

echo "$PW" > signing.txt
```

**Back up `kiosk-release.jks` and `signing.txt` somewhere safe** (in VS Code: right-click → Download), then delete them from the Codespace.

- If the key is lost, tablets that are already set up can't take updates. They would each need a factory reset and fresh set-up.
- GitHub won't let you read secrets back.
- `.gitignore` stops the key being committed by accident.

### 3. Build

- Push to `main`, or use Actions → *Build kiosk APK* → Run workflow.
- Each run publishes a GitHub Release containing:
  - `willowmere-kiosk.apk`
  - `provisioning-qr.png`: the set-up QR code
  - `provisioning.json`: what's inside the QR code
- The QR code always points at the *latest* release, so you only need to print it once, unless the signing key changes.

## Setting up a tablet (about 5 minutes)

1. Factory reset the tablet. A brand-new tablet is fine as it is.
2. On the first "Welcome / Start" screen, **tap the same spot 6 times**. A QR scanner opens.
3. Connect to Wi-Fi if asked, then scan `provisioning-qr.png`. You can show it on your phone screen.
4. Android downloads the kiosk app and makes it the device owner. Accept the prompts and skip anything optional.
5. The first screen asks for an **admin PIN** (6–12 digits) and confirms the Willowmere address. Save, and the tablet is locked.

Check the admin menu says **"Protection: full (device owner)"**.

**Test before deploying:** restart the tablet in kiosk mode, open the admin menu, join Wi-Fi, and use Exit for 30 minutes.

### Can't factory reset? (ADB route)

Install the APK, remove all accounts from the tablet, then from a computer run:

```
adb shell dpm set-device-owner com.heracaresolutions.kiosk/.KioskAdminReceiver
```

### Just trying it out

Installing the APK normally works too, as **basic protection** (Android screen pinning). In this mode, staff could still unpin it with Back + Recents unless "Ask for PIN before unpinning" is on in Android settings. Use the QR route for real deployments.

## Updating the app

1. Push changes; Actions publishes a new release.
2. On each tablet: admin menu → **Install kiosk app update**. The link is pre-filled with the latest release.
3. It installs silently and restarts the kiosk.

## Retiring a tablet

Admin menu → **Remove kiosk control from this tablet** → type `REMOVE`. You can then uninstall the app or factory reset as normal.

## Known limits (v1)

- **Web push notifications don't work in an Android WebView**, so Willowmere push alerts won't appear on kiosk tablets. In-app polling or alerts still work.
- Camera, microphone and location requests from the page are refused. File uploads use the system file picker.
- Some Samsung tablets add their own menus. Test one tablet of each model first.
- No remote management yet. Planned later.

## Project layout

```
app/src/main/java/com/heracaresolutions/kiosk/
  KioskActivity.java              kiosk browser, corner-tap gesture, offline screen
  AdminActivity.java              PIN-protected admin menu + first-run set-up
  KioskPolicy.java                device-owner lockdown (lock task, restrictions, home app)
  Prefs.java                      settings, PIN hashing (PBKDF2), URL allow-list
  PinDialog.java                  PIN prompt with lockout
  KioskAdminReceiver.java         device admin receiver
  ProvisioningModeActivity.java   QR set-up handshake (Android 12+)
  PolicyComplianceActivity.java   QR set-up handshake (Android 12+)
  BootReceiver.java               relaunch after restart / update
  RelockReceiver.java             ends the 30-minute exit
  UpdateInstaller.java            download + install update
  InstallResultReceiver.java      update install result
scripts/make-provisioning.sh      builds the QR code from the signed APK
.github/workflows/build.yml       build, sign, QR, release
```


## Brightness and idle time (27 Oct 2026)

- The **volume buttons change screen brightness**, not volume, in steps of 10% (5% to 100%).
  A badge shows the level; it is saved and re-applied after restarts.
- **Idle minutes** (admin menu, default 5): after this long untouched the kiosk returns to the
  start page, and Hera locks to the staff member's PIN after the same time. The kiosk tells
  Hera through its user agent (`HeraIdle/5`); Hera accepts 1 to 30. 0 leaves Hera on its own
  default of 5 minutes.
