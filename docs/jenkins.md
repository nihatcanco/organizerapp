# Deploying on Windows with Jenkins

The [`Jenkinsfile`](../Jenkinsfile) runs the tests, builds the Docker image and runs it on the Windows
machine that ran the build, on port 8080 unless you choose another. Jenkins checks the repository every
3 minutes and deploys each new commit to `main`; in a multibranch job, other branches are only tested.
Without Jenkins, follow [Running it](../README.md#running-it) instead.

Each deploy:

1. builds the Docker image, with the latest Java base image,
2. stops the running container and backs up the database inside the volume (see
   [Updating](../README.md#updating)),
3. starts a new container,
4. waits until the app answers; if it doesn't, the build fails and shows the log.

The container restarts by itself after a crash or a restart of Docker. Your data is in the Docker
volume `organizer-data`, and logs are shown by `docker logs organizer`.

## Settings

Settings are environment variables set in Jenkins, so they aren't kept in the repository. Add them under
*Manage Jenkins › System › Global properties › Environment variables*, or for one machine only under
*Manage Jenkins › Nodes › (the node) › Configure › Node Properties*. The next deploy uses them. All are
optional:

| Variable                             | Default    | Meaning                                                                         |
|--------------------------------------|------------|---------------------------------------------------------------------------------|
| `ORGANIZER_PORT`                     | `8080`     | Port the app answers on.                                                        |
| `ORGANIZER_PUBLIC_URL`               | *(none)*   | Address printed into QR labels, e.g. `http://organizer.lan:8080`.               |
| `ORGANIZER_PASSWORD_CREDENTIALS_ID`  | *(none)*   | ID of a *Secret text* credential holding the password the app asks for.         |
| `ORGANIZER_TIME_ZONE`                | UTC        | Time zone for dates, e.g. `Europe/Istanbul` ([list][zones]).                     |

For a password, add a *Secret text* credential under *Manage Jenkins › Credentials* and put its ID in
`ORGANIZER_PASSWORD_CREDENTIALS_ID`. The deploy also passes this machine's MachineGuid as
`ORGANIZER_MACHINE_ID`, which the saved API key is tied to (see
[How the API key is kept](../README.md#how-the-api-key-is-kept)).

[zones]: https://en.wikipedia.org/wiki/List_of_tz_database_time_zones

## The Windows machine needs

- Windows 10 (1803 or later)/11 or Windows Server 2019 or newer, 64-bit (x64). The deploy uses the
  `curl` that comes with these versions to check that the app is up.
- [Docker Desktop](https://docs.docker.com/desktop/setup/install/windows-install/) using Linux
  containers (the default). Docker Desktop starts when you sign in to Windows, so the app is
  available once someone has signed in after a restart. Turn on *Start Docker Desktop when you sign
  in* in its settings.
- JDK 21 (for example [Temurin 21](https://adoptium.net)), with `JAVA_HOME` set as a *system*
  environment variable, to run the tests.
- [Git for Windows](https://git-scm.com/download/win).
- A Jenkins agent with the label `windows` that can use Docker: one running as LocalSystem or as an
  administrator, or as a member of the `docker-users` group.
- The app's port open in Windows Firewall for incoming TCP connections, so phones and other computers
  can reach the app. The deploy doesn't change the firewall; add the rule yourself, preferably for
  private and domain networks only.

## Running Jenkins on your own Windows PC

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
6. Add any [settings](#settings) you want, then click **Build Now**. When it finishes, Organizer is at
   `http://localhost:8080`.
