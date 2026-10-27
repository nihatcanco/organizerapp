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
  ignores case and accents, and updates as you type. Press `/` on any page to jump to it.
- **QR labels** — print labels for drawers and boxes; scanning one with your phone camera opens that location.
- **Take photo** — opens the camera on phones, and the webcam on computers (photos are shrunk before uploading).
- **Fill in with AI** — with an Anthropic API key, Claude recognizes the item from its photo (a bag of
  resistors, a banana, a bottle of detergent), its typed name, or both, reads any label and fills in the
  name, quantity, tags and properties for you to check.
- **Exports** — CSV for spreadsheets, and a one-click backup of the whole database.

## Running it

The app runs in Docker. You need JDK 21 or newer to build it (Gradle finds an installed JDK 21
itself, or downloads one) and Docker to run it:

```sh
./gradlew buildFatJar
docker build -t organizer .
docker run -d --name organizer --restart unless-stopped -p 8080:8080 -v organizer-data:/data organizer
```

`docker logs organizer` shows where to reach it. Open `http://localhost:8080` on this computer,
or this computer's network address, such as `http://192.168.1.20:8080`, on your phone (it must be on
the same Wi-Fi). Tip: use "Add to Home Screen" in your phone's browser for an app-like icon.

Your data is kept in the `organizer-data` volume, so it survives updates.
Settings from [Configuration](#configuration) are passed with `-e`, e.g. `-e ORGANIZER_PASSWORD=drawers`.
To update by hand, build the jar and the image again, then:

```sh
docker stop --time 30 organizer && docker rm organizer
docker run --rm -v organizer-data:/data --entrypoint /app/backup.sh organizer   # back up the database
```

and run the `docker run` line above again.

## Deploying on Windows with Jenkins

The `Jenkinsfile` builds the app, runs the tests and runs the result in Docker on port 8080 of the
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
- Port 8080 open in Windows Firewall for incoming TCP connections, so phones and other computers
  can reach the app. The deploy doesn't change the firewall; add the rule yourself, preferably for
  private and domain networks only.

**Settings** are in the `environment` block of the `Jenkinsfile`: `PUBLIC_URL` and
`PASSWORD_CREDENTIALS_ID`. For a password, add a *Secret text* credential in Jenkins (*Manage Jenkins › Credentials*) and put its ID in
`PASSWORD_CREDENTIALS_ID`. Every build of `main` is deployed; in a multibranch job, other branches are only
built and tested.

### Running Jenkins on your own Windows PC

1. Install Docker Desktop, JDK 21 and Git for Windows, and set `JAVA_HOME` as a system variable.
2. Install [Jenkins LTS for Windows](https://www.jenkins.io/download/). In the installer:
   - choose **Run service as LocalSystem**;
   - change the port to something other than 8080, for example **8090**, because Organizer uses 8080;
   - pick your JDK 21.
3. Open `http://localhost:8090`, unlock Jenkins with the password the
   installer shows, and choose **Install suggested plugins**.
4. Go to *Manage Jenkins › Nodes › Built-In Node › Configure*, set **Labels** to `windows` and
   **Number of executors** to at least 1.
5. Create the job with *New Item › Pipeline*. Under **Pipeline**, choose *Pipeline script from SCM*,
   then *Git*, and enter the repository URL. Set the branch to `*/main` and the script path to
   `Jenkinsfile`. To build from a folder on the same PC instead of a remote repository, add
   `-Dhudson.plugins.git.GitSCM.ALLOW_LOCAL_CHECKOUT=true` to the `<arguments>` in
   `C:\Program Files\Jenkins\jenkins.xml`, restart the Jenkins service, and use the folder path,
   such as `C:\src\organizerapp`, as the URL.
6. Click **Build Now**. When it finishes, Organizer is at `http://localhost:8080`. After that,
   Jenkins checks the repository every 3 minutes and builds each new commit.

## Configuration

Everything is optional and set through environment variables, passed to the container with `-e`
(the `Jenkinsfile` sets each of them):

| Variable               | Default              | Meaning                                                                       |
|------------------------|----------------------|-------------------------------------------------------------------------------|
| `ORGANIZER_PASSWORD`   | *(none)*             | Ask for this password on every page (any username works).                     |
| `ORGANIZER_PUBLIC_URL` | *(from the request)* | Address printed into QR labels, e.g. `http://organizer.lan:8080`.              |
| `ORGANIZER_MACHINE_ID` | *(found by the app)* | This computer's ID, which the saved API key is tied to; needed in Docker (see [Filling in items with AI](#filling-in-items-with-ai)). |

The Anthropic API key for *Fill in with AI* isn't an environment variable: it's entered on the
**Settings** page (linked at the bottom of every page).

## Taking photos

On the item form, **Take photo** opens the camera:

- On phones and tablets it opens the camera app, and the photo goes straight into the form.
- On a computer with a webcam it shows the camera on the page. Press **Take photo** (or Enter) to use
  the picture, or **Switch camera** if there's more than one. Browsers only let pages use a webcam on
  `localhost` or over HTTPS, so the button appears when you open the app on the computer it runs on
  (`http://localhost:8080`) or through an HTTPS reverse proxy (see [Security](#security)), but not
  at an address like `http://192.168.1.20:8080`. **Choose photo** works everywhere.

## Filling in items with AI

With an Anthropic API key, the item form gets a **Fill in with AI** button. Claude (Anthropic's
Claude Haiku 5.5) works out what the item is from what you give it:

- **a photo**: it reads any label or packaging, such as bag labels from LCSC, Digi-Key and Mouser,
  the marking on a chip, or the front of a box or bottle;
- **a typed name**, such as `NE555P` or `Persil Universal Gel 1.5 L`: it fills in what it knows
  about that exact item;
- **both**: the name tells it what it's looking at, and the photo's label keeps it accurate.

It fills in the name, quantity, tags such as `resistor` and `smd` or `food` and `fruit`, properties
like brand, size, part number, manufacturer, package and voltage, and a short note. It only fills
empty fields, highlights what it filled in, and never saves anything by itself, so check the
suggestions before saving. Choosing a photo for a new item that has no name yet fills the form in
straight away.

To set it up:

1. Create an API key at [console.anthropic.com](https://console.anthropic.com/settings/keys) (the
   account needs some credit). A Claude.ai subscription doesn't include API use.
2. Open **Settings** (bottom of every page), paste the key and save. The app checks it with
   Anthropic first.

What it costs: Anthropic bills the account per use, about $0.0003 a photo and $0.0001 a typed name
(100 photos cost about 3¢). An answer takes about 2 seconds.

**Web search** is off by default. Switched on in Settings, Claude may look up details the photo or
name doesn't give, such as a part's specifications. It searches only when it needs to; each search
costs about 1¢ more and adds a few seconds.

### How the API key is kept

- It's checked with Anthropic, then stored in the database **encrypted** with AES-256-GCM. Pages
  never show it again, only its last four characters, and it isn't written to the logs.
- The encryption key is made from two parts: a random key file, `secret.key`, which the app creates
  next to the database (readable only by the app's user), and **this computer's ID**. *Download
  backup* and the update backups contain only the encrypted key, and a copy of the data, even with
  `secret.key`, can't decrypt it on another computer. After restoring on another computer, or a
  reinstall of the operating system, enter the key again in Settings.
- The computer's ID is Windows' MachineGuid, macOS's hardware UUID or Linux's `/etc/machine-id`. A
  Docker container can't see the computer it runs on, so there the ID is passed in as
  `ORGANIZER_MACHINE_ID`: the `Jenkinsfile` does this with the Windows machine's MachineGuid. When you
  run the container by hand, pass something unique to the computer, e.g. on Windows
  `-e ORGANIZER_MACHINE_ID=<the MachineGuid from HKLM\SOFTWARE\Microsoft\Cryptography>`. Without it
  the key is still encrypted, just not tied to the computer; Settings says so.
- Anyone who can open the app can *use* the key, though not see it. Set `ORGANIZER_PASSWORD` if
  others can reach the app.

## QR labels

Open a location and choose **Labels** to print codes for it and everything inside it (or use
**Locations › Labels** for all of them). Labels come in three sizes, and you can remove the ones
you don't need before printing.

The codes contain the address you opened the page with, so open it using this computer's network
address (e.g. `http://192.168.1.20:8080`), not `localhost`: inside Docker, the app can't find that
address itself, and the page warns you when the codes wouldn't work on a phone. If your computer's
IP changes, old labels stop working, so give the computer a fixed IP in your router, or set `ORGANIZER_PUBLIC_URL` to a
hostname. Labels contain short links (`/l/12`), and ids are never reused, so a label can't end up
pointing at something else.

## Your data

Everything, photos included, is in one SQLite file, `organizer.db` in the `organizer-data` volume.
Next to it, `secret.key` encrypts the saved API key (see [How the API key is kept](#how-the-api-key-is-kept)).
A `models/` folder left over from earlier versions, which ran an AI model inside the app, can be deleted.

- **Backup:** use *Download backup* in Settings or at the bottom of any page. It's safe to do while the app is
  in use. To restore, stop the app (`docker stop organizer`), then, in the folder
  with the downloaded file, run
  `docker run --rm -v organizer-data:/data -v "${PWD}:/restore" --entrypoint sh organizer -c "rm -f /data/organizer.db-wal && cp /restore/<file> /data/organizer.db"`
  with `<file>` replaced by its name, and start the app again (`docker start organizer`).
- **Export:** *Export CSV* gives you all items for a spreadsheet.

## Offline and private

The app has no accounts, analytics or update checks. Pages load nothing from other sites (no CDNs or
web fonts, and the browser is told to block anything that isn't from the app itself).

The internet is used only for:

- **Building** (`./gradlew buildFatJar` and `docker build`), which downloads Gradle, the libraries and
  the Java base image once. The built image doesn't need them.
- **Fill in with AI**, once an API key is saved: the item's photo and typed name go to Anthropic's
  API, when you press the button or choose a photo for a new item that has no name yet. Without a
  key, the app makes no outside connections at all.

## Security

There is no login by default: anyone who can reach the app on your network can view and edit
your inventory. Set `ORGANIZER_PASSWORD` if that matters to you. Don't expose the app directly to
the internet. If you want remote access, put it behind a reverse proxy with HTTPS (and the password),
or use a VPN such as Tailscale. Form posts from other websites are rejected, and the reverse proxy
must pass the original `Host` header through for that check to work.

## Development

```sh
./gradlew test   # unit tests plus HTTP tests against a temporary database; Claude is replaced by a stand-in
```

```
src/main/kotlin/organizer/
  Main.kt                    entry point and configuration
  ai/                        Fill in with AI: the Claude client, the question and its answer, the saved key
  db/                        SQLite access and schema migrations
  model/                     data classes, location tree, search normalization, name ranges
  store/                     repositories (all SQL lives here), settings and their encryption
  web/                       Ktor setup, routes and HTML views (kotlinx.html)
src/main/resources/static/   app.css, app.js (progressive enhancement, no build step)
```

What Claude is asked about each photo or name, and how its answer becomes item fields, is in
`ai/ItemSuggestion.kt`, with examples in `ItemSuggestionTest`; that's the place to change what it
fills in.

Schema changes go in `db/Migrations.kt` as a new entry; existing databases upgrade on startup.
