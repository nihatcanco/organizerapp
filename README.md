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

## Configuration

Everything is optional and set through environment variables:

| Variable               | Default              | Meaning                                                                 |
|------------------------|----------------------|-------------------------------------------------------------------------|
| `ORGANIZER_PORT`       | `8080`               | Port to listen on (`PORT` also works).                                  |
| `ORGANIZER_HOST`       | `0.0.0.0`            | Address to bind. `127.0.0.1` makes it reachable from this computer only. |
| `ORGANIZER_DB`         | `data/organizer.db`  | Where the SQLite database lives.                                        |
| `ORGANIZER_PASSWORD`   | *(none)*             | Ask for this password on every page (any username works).               |
| `ORGANIZER_PUBLIC_URL` | *(from the request)* | Address printed into QR labels, e.g. `http://organizer.lan:8080`.       |

Example: `ORGANIZER_PORT=9000 ORGANIZER_PASSWORD=drawers ./gradlew run`

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

Everything, photos included, is in one SQLite file (`data/organizer.db` by default).

- **Backup:** use *Download backup* at the bottom of any page. It's safe to do while the app is
  in use. To restore, stop the app and put the downloaded file in place of `organizer.db`.
- **Export:** *Export CSV* gives you all items for a spreadsheet.

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
```

```
src/main/kotlin/organizer/
  Main.kt                    entry point and configuration
  db/                        SQLite access and schema migrations
  model/                     data classes, location tree, search normalization, name ranges
  store/                     repositories (all SQL lives here)
  web/                       Ktor setup, routes and HTML views (kotlinx.html)
src/main/resources/static/   app.css, app.js (progressive enhancement, no build step)
```

Schema changes go in `db/Migrations.kt` as a new entry; existing databases upgrade on startup.
