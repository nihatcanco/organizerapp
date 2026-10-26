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
- **Take photo** — opens the camera on phones, and the webcam on computers (photos are shrunk before uploading).
- **Fill in from photo** — a small AI model that runs inside the app reads bag labels and part markings
  and fills in the name, quantity, tags and properties for you to check.
- **Exports** — CSV for spreadsheets, and a one-click backup of the whole database.

## Running it

The app runs in Docker. You need JDK 21 or newer to build it (Gradle finds an installed JDK 21
itself, or downloads one) and Docker to run it:

```sh
./gradlew buildFatJar
docker build -t organizer .
docker run -d --name organizer --restart unless-stopped -p 18420:18420 -v organizer-data:/data organizer
```

`docker logs organizer` shows where to reach it. Open `http://localhost:18420` on this computer,
or this computer's network address, such as `http://192.168.1.20:18420`, on your phone (it must be on
the same Wi-Fi). Tip: use "Add to Home Screen" in your phone's browser for an app-like icon.

Your data and the AI model are kept in the `organizer-data` volume, so they survive updates.
Settings from [Configuration](#configuration) are passed with `-e`, e.g. `-e ORGANIZER_PASSWORD=drawers`.
To update by hand, build the jar and the image again, then:

```sh
docker stop --time 30 organizer && docker rm organizer
docker run --rm -v organizer-data:/data --entrypoint /app/backup.sh organizer   # back up the database
```

and run the `docker run` line above again.

## Deploying on Windows with Jenkins

The `Jenkinsfile` builds the app, runs the tests and runs the result in Docker on port 18420 of the
Windows machine that ran the build. Without Jenkins, follow [Running it](#running-it) instead.

Each deploy:

1. builds the Docker image,
2. stops the running container and backs up the database inside the volume, in `backups/` (the
   last 5 are kept; see [`docker/backup.sh`](docker/backup.sh)). The backup is there for undoing an
   update: a new version may upgrade the database on its first start, and older versions can't
   read the upgraded file,
3. starts a new container,
4. waits until the app answers; if it doesn't, the build fails and shows the log.

The container restarts by itself after a crash or a restart of Docker. Your data is in the Docker
volume `organizer-data`, and logs are shown by `docker logs organizer`. To go back to a backup:

1. Stop the app with `docker stop organizer`.
2. List the backups with
   `docker run --rm -v organizer-data:/data --entrypoint ls organizer /data/backups`.
3. Copy one over the database, replacing `<backup>` with its name:
   `docker run --rm -v organizer-data:/data --entrypoint sh organizer -c "rm -f /data/organizer.db-wal && cp /data/backups/<backup>/* /data/"`
4. Start the app with `docker start organizer`.

**The Windows machine needs:**

- Windows 10 (1803 or later)/11 or Windows Server 2019 or newer, 64-bit (x64). The deploy uses the
  `curl` that comes with these versions to check that the app is up.
- [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) using Linux
  containers (the default). Docker Desktop starts when you sign in to Windows, so the app is
  available once someone has signed in after a restart. Turn on *Start Docker Desktop when you sign
  in* in its settings.
- JDK 21 (for example [Temurin 21](https://adoptium.net)), with `JAVA_HOME` set as a *system*
  environment variable, to build the app.
- [Git for Windows](https://git-scm.com/download/win).
- A Jenkins agent with the label `windows` that can use Docker: one running as LocalSystem or as an
  administrator, or as a member of the `docker-users` group.
- Port 18420 open in Windows Firewall for incoming TCP connections, so phones and other computers
  can reach the app. The deploy doesn't change the firewall; add the rule yourself, preferably for
  private and domain networks only.

**Settings** are in the `environment` block of the `Jenkinsfile`: `PUBLIC_URL`, `AI` and
`PASSWORD_CREDENTIALS_ID`. For a password, add a *Secret text* credential in Jenkins (*Manage
Jenkins › Credentials*) and put its ID in `PASSWORD_CREDENTIALS_ID`. Every build of `main` is
deployed; in a multibranch job, other branches are only built and tested.

### Running Jenkins on your own Windows PC

1. Install Docker Desktop, JDK 21 and Git for Windows, and set `JAVA_HOME` as a system variable.
2. Install [Jenkins LTS for Windows](https://www.jenkins.io/download/). In the installer:
   - choose **Run service as LocalSystem**;
   - keep the suggested port (8080) unless another app already uses it;
   - pick your JDK 21.
3. Open `http://localhost:8080` (or the port you chose), unlock Jenkins with the password the
   installer shows, and choose **Install suggested plugins**.
4. Go to *Manage Jenkins › Nodes › Built-In Node › Configure*, set **Labels** to `windows` and
   **Number of executors** to at least 1.
5. Create the job with *New Item › Pipeline*. Under **Pipeline**, choose *Pipeline script from SCM*,
   then *Git*, and enter the repository URL. Set the branch to `*/main` and the script path to
   `Jenkinsfile`. To build from a folder on the same PC instead of a remote repository, add
   `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` to the `<arguments>` in
   `C:\Program Files\Jenkins\jenkins.xml`, restart the Jenkins service, and use the folder path,
   such as `C:\src\organizerapp`, as the URL.
6. Click **Build Now**. When it finishes, Organizer is at `http://localhost:18420`. After that,
   Jenkins checks the repository every 3 minutes and builds each new commit.

## Configuration

Everything is optional and set through environment variables, passed to the container with `-e`
(the `Jenkinsfile` has a setting for each):

| Variable               | Default              | Meaning                                                           |
|------------------------|----------------------|-------------------------------------------------------------------|
| `ORGANIZER_PASSWORD`   | *(none)*             | Ask for this password on every page (any username works).         |
| `ORGANIZER_PUBLIC_URL` | *(from the request)* | Address printed into QR labels, e.g. `http://organizer.lan:18420`. |
| `ORGANIZER_AI`         | `on`                 | `off` hides "Fill in from photo".                                 |

## Taking photos

On the item form, **Take photo** opens the camera:

- On phones and tablets it opens the camera app, and the photo goes straight into the form.
- On a computer with a webcam it shows the camera on the page. Press **Take photo** (or Enter) to use
  the picture, or **Switch camera** if there's more than one. Browsers only let pages use a webcam on
  `localhost` or over HTTPS, so the button appears when you open the app on the computer it runs on
  (`http://localhost:18420`) or through an HTTPS reverse proxy (see [Security](#security)), but not
  at an address like `http://192.168.1.20:18420`. Choosing a photo file works everywhere.

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
  the `organizer-data` volume. The files are pinned to an exact version and checked against SHA-256 checksums.
- Reading a photo took about 1–1.5 seconds on an M3 Max; expect longer on older or smaller machines
  such as a Raspberry Pi. The model is loaded on first use, after which the app uses about 1.5 GB
  of memory.

Tips: photograph the label straight on, close enough to fill most of the frame, in good light. Small
models misread now and then, especially runs of the same digit (NE555P can come out as NE55P), so
glance over part numbers.

## QR labels

Open a location and choose **Labels** to print codes for it and everything inside it (or use
**Locations › Labels** for all of them). Labels come in three sizes, and you can remove the ones
you don't need before printing.

The codes contain the address you opened the page with, so open it using this computer's network
address (e.g. `http://192.168.1.20:18420`), not `localhost`: inside Docker, the app can't find that
address itself, and the page warns you when the codes wouldn't work on a phone. If your computer's
IP changes, old labels stop working, so give the computer a fixed IP in your router, or set `ORGANIZER_PUBLIC_URL` to a
hostname. Labels contain short links (`/l/12`), and ids are never reused, so a label can't end up
pointing at something else.

## Your data

Everything, photos included, is in one SQLite file, `organizer.db` in the `organizer-data` volume.
The AI model in the volume's `models/` folder isn't part of your data; it can be deleted and
downloaded again at any time.

- **Backup:** use *Download backup* at the bottom of any page. It's safe to do while the app is
  in use. To restore, stop the app (`docker stop organizer`), then, in the folder
  with the downloaded file, run
  `docker run --rm -v organizer-data:/data -v "${PWD}:/restore" --entrypoint sh organizer -c "rm -f /data/organizer.db-wal && cp /restore/<file> /data/organizer.db"`
  with `<file>` replaced by its name, and start the app again (`docker start organizer`).
- **Export:** *Export CSV* gives you all items for a spreadsheet.

## Offline and private

The app needs no internet connection and has no accounts, analytics or update checks. Pages load
nothing from other sites (no CDNs or web fonts, and the browser is told to block anything that
isn't from the app itself), and photos are read on your computer.

The internet is used only twice:

- **Building** (`./gradlew buildFatJar` and `docker build`) downloads Gradle, the libraries and the
  Java base image once. The built image doesn't need them.
- **The AI model download**, which happens only when you agree to it, fetches the model from
  Hugging Face once. If you don't use "Fill in from photo", set `ORGANIZER_AI=off` and the app
  makes no outside connections at all.

To set up the model on a machine without internet, download these five files from
[the pinned version](https://huggingface.co/onnx-community/Florence-2-base-ft/tree/e88a44eaf3791a35eae0c5a47b3dbcd36e67eb6f)
and put them, without their folders, in a folder named `florence-2-base-ft`:
`tokenizer.json`, `onnx/vision_encoder.onnx`, `onnx/embed_tokens_quantized.onnx`,
`onnx/encoder_model_quantized.onnx` and `onnx/decoder_model_merged_quantized.onnx`. Then, next to
that folder, copy it into the volume and restart the app, which finds the files on startup:

```sh
docker run --rm -v organizer-data:/data -v "${PWD}:/import" --entrypoint sh organizer -c "mkdir -p /data/models && cp -R /import/florence-2-base-ft /data/models/"
docker restart organizer
```

You can also copy the folder out of a machine where the app downloaded it, with
`docker cp organizer:/data/models/florence-2-base-ft .`

## Security

There is no login by default: anyone who can reach the app on your network can view and edit
your inventory. Set `ORGANIZER_PASSWORD` if that matters to you. Don't expose the app directly to
the internet. If you want remote access, put it behind a reverse proxy with HTTPS (and the password),
or use a VPN such as Tailscale. Form posts from other websites are rejected, and the reverse proxy
must pass the original `Host` header through for that check to work.

## Development

```sh
./gradlew test   # unit tests plus HTTP tests against a temporary database

# Also run the tests that need the real AI model, from a folder holding it (see above):
./gradlew test -Dorganizer.test.models=path/to/florence-2-base-ft
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
