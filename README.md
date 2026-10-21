# Organizer

A small self-hosted inventory app for keeping track of where your things are — built for
electronic components spread across drawers, but it works for anything. It runs as a web
server on your computer, and you use it from any browser on your network: desktop or phone.

Kotlin + Ktor on the server, SQLite for storage, server-rendered HTML with a little JavaScript.

## Features

- **Nested locations** — e.g. Workshop › Parts cabinet › Drawer A3. Create a whole cabinet at once
  with ranges: `Drawer {1-24}`, `{A-D}{1-6}` (A1…D6) or `Bin {01-12}`.
- **Items** with quantity, tags, free-form properties (`Package: 0805`, `Voltage: 16 V`), notes and a photo.
- **Quick − / + buttons** to update stock right from any list, plus optional low-stock warnings.
- **Search** across names, tags, properties and notes. Matches parts of words (`317` finds `LM317T`),
  ignores case and accents, and updates as you type.
- **QR labels** — print labels for drawers and boxes; scanning one with your phone camera opens that location.
- **Phone-friendly** — take photos straight from the camera (they're shrunk before uploading).
- **Fill in from photo** — a small AI model that runs inside the app reads bag labels and part markings
  and fills in the name, quantity, tags and properties for you to check.
- **Exports** — CSV for spreadsheets, and a one-click backup of the whole database.

## Running it

You need JDK 21 or newer. Gradle finds an installed JDK 21 itself (or downloads one).

```sh
./gradlew run
```

The console shows where to reach it:

```
Organizer is running.
  On this computer: http://localhost:8080
  On your network:  http://192.168.1.20:8080
  Database:         /…/organizerapp/data/organizer.db
```

Open the **network address** on your phone (it must be on the same Wi-Fi). On macOS, allow
incoming connections for Java when the firewall asks. Tip: use "Add to Home Screen" in your
phone's browser for an app-like icon.

### Standalone jar

```sh
./gradlew buildFatJar
java -jar build/libs/organizer.jar
```

The jar is self-contained, so you can copy it to a home server or Raspberry Pi (anything with Java 21+).

## Deploying on Windows with Jenkins

The `Jenkinsfile` builds the app, runs the tests and installs the result on the Windows machine that
ran the build. The app runs as a scheduled task under the SYSTEM account and starts with Windows,
even when nobody is logged in. Installing and updating are done by
[`deploy/windows/deploy.ps1`](deploy/windows/deploy.ps1), which you can also run without Jenkins.

Each deploy:

1. stops the running app and backs up the database to `C:\Organizer\backups\` (the last 5 are kept),
2. copies in the new `organizer.jar`,
3. registers the scheduled task and lets port 8080 through the firewall on private and domain
   networks (not public ones),
4. starts the app and waits until it answers; if it doesn't, the build fails and shows the log.

Your data stays in `C:\Organizer\data\` between deploys, and logs are in `C:\Organizer\logs\`.
To go back to a backup:

1. Stop the app (`deploy.ps1 -Stop`, see below).
2. Delete the `organizer.db*` files in `data\`.
3. Copy in the files from the backup folder (`organizer.db`, and `organizer.db-wal` if there is one).
4. Start the app with `Start-ScheduledTask Organizer`.

**The Windows machine needs:**

- Windows 10/11 or Windows Server 2019 or newer, 64-bit (x64).
- JDK 21 (for example [Temurin 21](https://adoptium.net)), with `JAVA_HOME` set as a *system*
  environment variable. The build and the installed app both use it.
- [Git for Windows](https://git-scm.com/download/win).
- The [Microsoft Visual C++ Redistributable](https://learn.microsoft.com/cpp/windows/latest-supported-vc-redist)
  (x64), which ONNX Runtime needs for photo reading. Many PCs already have it.
- A Jenkins agent with the label `windows`. It has to run as an administrator, for example
  as LocalSystem, because it registers a scheduled task and a firewall rule.

**Job parameters** (they appear after the first build): `DEPLOY` (turn it off to only build and
test), `DEPLOY_DIR` (default `C:\Organizer`), `PORT`, `PUBLIC_URL`, `AI` and
`PASSWORD_CREDENTIALS_ID`. For a password, add a *Secret text* credential in Jenkins (*Manage Jenkins
› Credentials*) and put its ID in `PASSWORD_CREDENTIALS_ID`. In a multibranch job, only `main` is
deployed.

### Running Jenkins on your own Windows PC

1. Install JDK 21 and Git for Windows, and set `JAVA_HOME` as a system variable.
2. Install [Jenkins LTS for Windows](https://www.jenkins.io/download/). In the installer:
   - choose **Run service as LocalSystem**;
   - change the port to something other than 8080, for example **8090**, because Organizer uses 8080;
   - pick your JDK 21.
3. Open `http://localhost:8090`, unlock Jenkins with the password the installer shows, and choose
   **Install suggested plugins**.
4. Go to *Manage Jenkins › Nodes › Built-In Node › Configure*, set **Labels** to `windows` and
   **Number of executors** to at least 1.
5. Create the job with *New Item › Pipeline*. Under **Pipeline**, choose *Pipeline script from SCM*,
   then *Git*, and enter the repository URL. Set the branch to `*/main` and the script path to
   `Jenkinsfile`. To build from a folder on the same PC instead of a remote repository, add
   `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` to the `<arguments>` in
   `C:\Program Files\Jenkins\jenkins.xml`, restart the Jenkins service, and use the folder path,
   such as `C:\src\organizerapp`, as the URL.
6. Click **Build Now**. When it finishes, Organizer is at `http://localhost:8080`. Later builds
   show **Build with Parameters**.

### Without Jenkins

In an administrator PowerShell, in the project folder:

```powershell
.\gradlew.bat buildFatJar
powershell -ExecutionPolicy Bypass -File deploy\windows\deploy.ps1 -Jar build\libs\organizer.jar
```

Add `-Port`, `-DeployDir`, `-PublicUrl` or `-AI off` to change the defaults. For a password, set
`$env:ORGANIZER_PASSWORD = '…'` first. `-Stop` stops the app until Windows restarts
(`Start-ScheduledTask Organizer` starts it again). `-Uninstall` also removes the scheduled task
and the firewall rule; your data stays in the deploy folder.

## Configuration

Everything is optional and set through environment variables:

| Variable               | Default              | Meaning                                                                 |
|------------------------|----------------------|-------------------------------------------------------------------------|
| `ORGANIZER_PORT`       | `8080`               | Port to listen on (`PORT` also works).                                  |
| `ORGANIZER_HOST`       | `0.0.0.0`            | Address to bind. `127.0.0.1` makes it reachable from this computer only. |
| `ORGANIZER_DB`         | `data/organizer.db`  | Where the SQLite database lives.                                        |
| `ORGANIZER_PASSWORD`   | *(none)*             | Ask for this password on every page (any username works).               |
| `ORGANIZER_PUBLIC_URL` | *(from the request)* | Address printed into QR labels, e.g. `http://organizer.lan:8080`.       |
| `ORGANIZER_AI`         | `on`                 | `off` hides "Fill in from photo".                                       |
| `ORGANIZER_MODELS`     | `data/models`        | Where the AI model is stored (next to the database by default).         |

Example: `ORGANIZER_PORT=9000 ORGANIZER_PASSWORD=drawers ./gradlew run`

## Filling in items from photos

On the item form, choose or take a photo and tap **Fill in from photo**. The app reads the text
on the photo (bag labels from LCSC, Digi-Key, Mouser and similar, or the marking on a chip) and fills
in what it finds: the name, quantity, tags such as `resistor` and `smd`, and properties like part
number, manufacturer, resistance, capacitance, voltage, tolerance and package. It only fills empty
fields and never saves anything by itself, so check the suggestions before saving. Once the model is
downloaded, choosing a photo for a new item fills the form in automatically.

How it works:

- The model is Microsoft's [Florence-2-base](https://huggingface.co/microsoft/Florence-2-base-ft)
  (MIT licence), a small vision model good at reading text. It runs inside the app with
  [ONNX Runtime](https://onnxruntime.ai), so there is nothing else to install and photos never leave
  your computer.
- The first time you use it, the app asks before downloading the model (about 550 MB, once) into
  `data/models/`. The files are pinned to an exact version and checked against SHA-256 checksums.
- Reading a photo took about 1–1.5 seconds on an M3 Max; expect longer on older or smaller machines
  such as a Raspberry Pi. The model is loaded on first use, after which the app uses about 1.5 GB
  of memory.

Tips: photograph the label straight on, close enough to fill most of the frame, in good light. Small
models misread now and then, especially runs of the same digit (NE555P can come out as NE55P), so
glance over part numbers. If reading takes much longer than a few seconds on a Mac, start the app
from Terminal: macOS gives programs launched from apps in the background (such as an editor's
built-in terminal) a low priority.

## QR labels

Open a location and choose **Labels** to print codes for it and everything inside it (or use
**Locations › Labels** for all of them). Labels come in three sizes, and you can remove the ones
you don't need before printing.

The codes contain this computer's network address. If you opened the page as `localhost`, the
app substitutes your network address automatically. If your computer's IP changes, old labels stop
working, so give the computer a fixed IP in your router, or set `ORGANIZER_PUBLIC_URL` to a
hostname. Labels contain short links (`/l/12`), and ids are never reused, so a label can't end up
pointing at something else.

## Your data

Everything, photos included, is in one SQLite file (`data/organizer.db` by default). The AI model in
`data/models/` isn't part of your data; it can be deleted and downloaded again at any time.

- **Backup:** use *Download backup* at the bottom of any page. It's safe to do while the app is
  in use. To restore, stop the app and put the downloaded file in place of `organizer.db`.
- **Export:** *Export CSV* gives you all items for a spreadsheet.

## Offline and private

The app needs no internet connection and has no accounts, analytics or update checks. Pages load
nothing from other sites (no CDNs or web fonts, and the browser is told to block anything that
isn't from the app itself), and photos are read on your computer.

The internet is used only twice:

- **Building** (`./gradlew run` or `buildFatJar`) downloads Gradle and the libraries once. The
  built jar doesn't need them.
- **The AI model download**, which happens only when you agree to it, fetches the model from
  Hugging Face once. If you don't use "Fill in from photo", set `ORGANIZER_AI=off` and the app
  makes no outside connections at all.

To set up the model on a machine without internet, download these five files from
[the pinned version](https://huggingface.co/onnx-community/Florence-2-base-ft/tree/e88a44eaf3791a35eae0c5a47b3dbcd36e67eb6f)
and put them, without their folders, in `data/models/florence-2-base-ft/`:
`tokenizer.json`, `onnx/vision_encoder.onnx`, `onnx/embed_tokens_quantized.onnx`,
`onnx/encoder_model_quantized.onnx` and `onnx/decoder_model_merged_quantized.onnx`. You can also
copy that folder from a machine where the app downloaded it. The app finds the files on startup.

## Security

There is no login by default: anyone who can reach the app on your network can view and edit
your inventory. Set `ORGANIZER_PASSWORD` if that matters to you. Don't expose the app directly to
the internet. If you want remote access, put it behind a reverse proxy with HTTPS (and the password),
or use a VPN such as Tailscale. Form posts from other websites are rejected, and the reverse proxy
must pass the original `Host` header through for that check to work.

## Development

```sh
./gradlew test   # unit tests plus HTTP tests against a temporary database
./gradlew run    # start the server

# Also run the tests that need the real AI model (download it once through the app first):
./gradlew test -Dorganizer.test.models=data/models/florence-2-base-ft
```

```
src/main/kotlin/organizer/
  Main.kt                    entry point and configuration
  ai/                        photo reading: Florence-2 on ONNX Runtime, tokenizer, label parsing, model download
  db/                        SQLite access and schema migrations
  model/                     data classes, location tree, search normalization, name ranges
  store/                     repositories (all SQL lives here)
  web/                       Ktor setup, routes and HTML views (kotlinx.html)
src/main/resources/static/   app.css, app.js (progressive enhancement, no build step)
```

The rules that turn the text read from a photo into item fields are in `ai/ItemSuggestion.kt`, with
examples in `ItemSuggestionTest`; that's the place to teach it a new label format.

Schema changes go in `db/Migrations.kt` as a new entry; existing databases upgrade on startup.
