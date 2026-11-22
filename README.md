# Halbwach

*German for "half awake".*

Turns an Android tablet into a Home Assistant wall panel whose display goes **truly black** while the dashboard **keeps running live**. Wake it with a double tap or from Home Assistant. Includes a camera for Home Assistant and a wall mode that only kicks in while the tablet hangs in its mount.

Built and tested on a **Honor Pad X8a** (NDL-W09, MagicOS 10 / Android 16) with the Home Assistant Companion app. Other devices will probably work, but the double-tap wake gesture is Honor-specific (see [How it works](#how-it-works)).

## Features

- **Display off, dashboard on:** after a configurable time without touch (default 10 s) only the display panel is switched off. To Android the screen stays on, so the Companion app does not pause its WebView and the dashboard is up to date the instant you wake it. On an LCD there is no gray glow like at 0 % brightness.
- **Wake up:** double tap the black display, or call `http://<tablet>:8765/on` from Home Assistant (e.g. from a presence sensor).
- **No blind taps:** while the panel is off, the touchscreen is grabbed exclusively.
- **Pin Home Assistant:** the service opens the Companion app and pins it (screen pinning), also after a reboot. If pinning was released, it pins the app again before the panel turns off.
- **Wall mode (optional):** active only while the tablet hangs straight (landscape or portrait), vertical and still. Taken off the wall it is a normal tablet again: display on, your normal screen timeout, pinning released.
- **Camera (optional):** snapshot `/camera.jpg` and MJPEG stream `/camera.mjpg` for Home Assistant. The camera only runs while something is fetching images and turns off 5 s afterwards. Android shows the green privacy dot meanwhile.
- **Autostart:** as soon as Shizuku runs (e.g. after a reboot), the service starts by itself.

## How it works

Two parts:

- **`daemon/`**: a Java program that runs as the **shell user** through `app_process` (like scrcpy). Only with those privileges can it switch the panel off via `SurfaceControl.setDisplayPowerMode`, read the touchscreen (`/dev/input/event4`) and open the camera without an app context. It offers a small HTTP interface on port 8765.
- **`app/`**: the Android app "Halbwach". It starts the service through **Shizuku** (which provides shell privileges without a PC) and offers settings, status and the log. The service is bundled inside the APK.

Key findings behind it:

- Since version 2026.8 the Companion app pauses its WebView as soon as Android turns the screen off (`pauseTimers`, [home-assistant/android#7135](https://github.com/home-assistant/android/pull/7135)). A real screen-off therefore freezes the dashboard. Fully Kiosk has the same problem (an Android WebView limit).
- Honor's touch driver (`honor,ts_kit`) reports no touches while the panel is off, but reports the double-tap gesture as the key **`KEY_F1`**. The gesture is only armed after Android really went to sleep once, so the service does a short `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` on start; afterwards it stays armed.
- With the panel off, Android would still fall asleep after its screen timeout (no touches arrive). The service sets the timeout to the maximum while it is active and afterwards restores exactly the value you had set (including "never"). The remembered value is also kept in `/data/local/tmp/halbwach.timeout` in case the service is killed.
- ART does not run shutdown hooks on `SIGTERM`. The service is stopped cleanly through the stop file `/data/local/tmp/halbwach.stop` (the app does that) or `http://<tablet>:8765/stop`.
- On MagicOS, `am start -n <activity>` fails for the Companion app with "Activity class … does not exist". The service starts it through the launcher intent (`monkey -p <package> -c android.intent.category.LAUNCHER 1`) and pins it with `am task lock <taskId>`.

## Building

Requirements (tested on CachyOS/Arch): Android SDK with `platforms/android-36` and `build-tools/36.0.0` (default `~/Android/Sdk`, override with `ANDROID_HOME`), **JDK 21** (default `/usr/lib/jvm/java-21-openjdk`, override with `JAVA_HOME`), `python3`, `curl`. No Gradle needed.

```bash
app/build.sh
```

Output: `app/build/halbwach.apk`. On the first run the script downloads the Shizuku API from Maven Central and creates a signing key `app/keystore.jks` (password `halbwach`, override with `HALBWACH_KEYSTORE_PASS`).

> **Back up the signing key!** Updates can only be installed over the existing app if they are signed with the same key. `keystore.jks` is deliberately not in the repository. Without it you have to uninstall and reinstall, which loses the settings.

`daemon/build.sh` builds only the service as a `.dex`, for testing over adb without the app (`daemon/tablet.sh start|stop|log|status`).

## Setup

### 1. Prepare the tablet

1. Enable developer options (tap the build number 7 times), turn on **USB debugging**.
2. Connect the tablet over USB and allow the connection ("always allow from this computer").
3. In the Companion app: **Profile → "Automatically close connection" off** (this is stored per device/browser).
4. MagicOS: **Settings → Battery → App launch** for **Home Assistant**, **Shizuku** and **Halbwach**: "Manage manually" with **all three switches on**. Otherwise MagicOS kills or blocks the apps in the background.

### 2. Shizuku

Install [Shizuku](https://shizuku.rikka.app/) (Play Store) or the fork by thedjchi from [github.com/thedjchi/Shizuku/releases](https://github.com/thedjchi/Shizuku/releases) (tested: v13.7.0), which adds TCP mode and a watchdog. For the fork:

```bash
adb install shizuku-v13.7.0-thedjchi.apk
adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
```

Then pair Shizuku over **wireless debugging** (enter the pairing code from the developer options into Shizuku's notification) and start it.

### 3. Halbwach

```bash
adb install -r app/build/halbwach.apk
```

Open the app, **grant the Shizuku permission**, choose your settings and tap **Start**. With "Start automatically" this happens by itself from then on, whenever Shizuku is running.

| Setting | Meaning |
|---|---|
| Display off after | seconds without touch (0 = never automatically) |
| Start automatically | start the service as soon as Shizuku runs |
| Pin Home Assistant | open and pin the Companion app |
| Only in the wall mount | wall mode; tolerance in degrees, settle time in seconds of stillness |
| Token | required on every request as `?token=…`. **Always set one when the camera is on.** |
| Camera | Off / Front / Back |

Settings apply after restarting the service (Stop, Start).

## Home Assistant

`configuration.yaml` (adjust address and token, and give the tablet a fixed IP in your router):

```yaml
rest_command:
  wall_tablet_on:
    url: "http://TABLET-IP:8765/on?token=TOKEN"
  wall_tablet_off:
    url: "http://TABLET-IP:8765/off?token=TOKEN"
```

Example: turn the display on when someone enters the room.

```yaml
automation:
  - alias: Wake the wall tablet on presence
    triggers:
      - trigger: state
        entity_id: binary_sensor.hallway_presence
        to: "on"
    actions:
      - action: rest_command.wall_tablet_on
```

Camera: **Settings → Devices & services → Add integration → "MJPEG IP Camera"**

- MJPEG URL: `http://TABLET-IP:8765/camera.mjpg?token=TOKEN`
- Still image URL: `http://TABLET-IP:8765/camera.jpg?token=TOKEN`

`/status` returns JSON, for example `{"panel":"off","android_awake":true,"camera":"ready","wall":true,"tilt":1.2,"deviation":0.8}`.

## Recording with Frigate (on motion)

Home Assistant does not record by itself. For motion recordings with a retention period, **Frigate** is the tool of choice. Based on the Frigate 0.18 documentation.

1. Install the **Mosquitto broker** app/add-on if you do not have one; the Frigate integration needs MQTT.
2. **Settings → Apps (Add-ons) → App store → ⋮ → Repositories**: add `https://github.com/blakeblackshear/frigate-hass-addons`, then install **Frigate**.
3. Frigate configuration (in Frigate's settings or `frigate.yml`):

```yaml
mqtt:
  host: core-mosquitto
  user: MQTT_USER
  password: MQTT_PASSWORD

go2rtc:
  streams:
    wall_tablet:
      # Pull the MJPEG stream from the tablet and convert it to H.264 for recording
      - "ffmpeg:http://TABLET-IP:8765/camera.mjpg?token=TOKEN#video=h264"

cameras:
  wall_tablet:
    ffmpeg:
      inputs:
        - path: rtsp://127.0.0.1:8554/wall_tablet
          input_args: preset-rtsp-restream
          roles:
            - detect
            - record
    detect:
      width: 640
      height: 480
      fps: 5
    objects:
      track:
        - person
    record:
      enabled: true
      motion:
        days: 3
      alerts:
        retain:
          days: 14
          mode: motion
      detections:
        retain:
          days: 14
          mode: motion
```

4. Set up the **Frigate integration** in Home Assistant ([docs](https://docs.frigate.video/integrations/home-assistant)) to get the camera, motion sensors and clips in HA.

Not tested on this setup yet: the go2rtc conversion `ffmpeg:…#video=h264` of an MJPEG source. If it does not work, check the go2rtc section of the Frigate log first.

**Keep in mind:**

- Frigate reads the stream around the clock, so the tablet's camera is permanently on (green dot, more heat and power). Heat is bad for the battery; limiting charging to 20–80 % matters even more.
- Anyone living in or visiting the home should know about the camera.

## After a reboot

1. Unlock the tablet (the PIN is always required after a reboot).
2. Open **Shizuku** and start it over wireless debugging.
3. Done: Halbwach starts the service by itself, and the service pins Home Assistant.

The fork's "Start on boot" does not work reliably on the Honor yet (as of November 2026): after enabling wireless debugging it searches for the port over mDNS for 15 s, fails and then retries with ever longer pauses. So starting it by hand remains the way.

Good to know:

- The fork turns wireless debugging off again after **every** start attempt. That is intentional, not a bug.
- In **TCP mode** (Shizuku settings), `adbd` listens on port 5555 until the next reboot. With the **watchdog** enabled, Shizuku then restarts itself after a crash without wireless debugging. The panel service keeps running independently of Shizuku unless it is stopped itself.
- If a start hangs: force stop Shizuku, enable wireless debugging, start again.
- **Unplugging from a PC stops Shizuku and the panel service.** MagicOS restarts `adbd` on USB disconnect, and Android kills everything started through it. Plugging and unplugging the charger is fine (tested). After unplugging from a PC, start Shizuku again, or let the watchdog in TCP mode do it.

## Troubleshooting

- **Log:** in the app under "Log", or `adb shell cat /data/local/tmp/halbwach.log`.
- **Display stays dark and does not react:** press the power button twice. Android then really sleeps once and turns the panel on by itself when waking up.
- **Double tap does nothing:** look for "Double-tap gesture detected" in the log. If it is missing, the gesture was not armed. The service also wakes on a normal quick double tap, as long as the driver reports touches.
- **Home Assistant does not get pinned:** look for "Cannot start app" in the log, and check the MagicOS app launch setting (Setup, step 4).
- **Camera image rotated:** add the start argument `rotation=90` (or 180/270); currently only through `daemon/tablet.sh`.
- **Testing without the app:** `daemon/build.sh && daemon/tablet.sh start idle=10 camera=front wall=1` (all arguments are listed at the top of `Main.java`).

## License

Apache License 2.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). Parts of the display and camera control follow [scrcpy](https://github.com/Genymobile/scrcpy).
