// Tests Organizer, then builds its Docker image and runs it on the Windows agent that ran the build. The container
// isn't a child of the build, so it keeps running after the build ends. See docs/jenkins.md.
//
// Settings are environment variables set in Jenkins, not here, so they stay out of the repository (see
// "Settings" in docs/jenkins.md). Left unset, the app runs on port 18420 without a password.
pipeline {
    agent { label 'windows' }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    triggers {
        // Checks GitHub every 3 minutes. H picks a fixed offset per job, so jobs with the same schedule
        // don't all poll at the same moment.
        pollSCM('H/3 * * * *')
    }

    stages {
        stage('Test') {
            steps {
                // No daemon: Jenkins ends the build's processes when it finishes anyway.
                bat 'gradlew.bat --no-daemon clean test'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Deploy') {
            when {
                // In a multibranch job only main is deployed; other branches are just tested.
                expression { env.BRANCH_NAME == null || env.BRANCH_NAME == 'main' }
            }
            steps {
                script {
                    // Checked before anything is stopped, since they go into command lines. Not 8080, which other
                    // apps on the same machine often use.
                    def port = env.ORGANIZER_PORT?.trim() ?: '18420'
                    if (!(port ==~ /\d{1,5}/)) {
                        error "ORGANIZER_PORT must be a port number, not '${port}'."
                    }
                    def timeZone = env.ORGANIZER_TIME_ZONE?.trim() ?: ''
                    if (!(timeZone ==~ /[A-Za-z0-9_+\/-]*/)) {
                        error "ORGANIZER_TIME_ZONE must be a name such as Europe/Istanbul, not '${timeZone}'."
                    }
                    def passwordId = env.ORGANIZER_PASSWORD_CREDENTIALS_ID?.trim()

                    // This Windows machine's ID. The container can't see the computer it runs on, and the API key
                    // saved in Settings is encrypted so that only this computer can decrypt it (see README.md).
                    // Read before the running app is stopped, so a failure here leaves it running.
                    def machineId = powershell(returnStdout: true, script: '[Microsoft.Win32.RegistryKey]::OpenBaseKey(' +
                        "'LocalMachine', 'Registry64').OpenSubKey('SOFTWARE\\Microsoft\\Cryptography').GetValue('MachineGuid')").trim()
                    if (!machineId) {
                        error "Couldn't read this computer's MachineGuid from the registry."
                    }

                    // --pull, so the Java base image gets its security updates too.
                    bat 'docker build --pull --tag organizer .'
                    if (bat(returnStatus: true, script: '@docker container inspect organizer >nul 2>&1') == 0) {
                        // Gives the app time to close the database before the backup.
                        bat 'docker stop --time 30 organizer && docker rm organizer'
                    }
                    bat 'docker run --rm --volume organizer-data:/data --entrypoint /app/backup.sh organizer'

                    // Settings reach the container as environment variables (--env with a name only), so no
                    // value, the password included, is pasted into a command line. TZ is only set inside the
                    // container, where it means the time zone; Windows programs read it differently.
                    def settings = [
                        "ORGANIZER_PORT=${port}",
                        "ORGANIZER_PUBLIC_URL=${env.ORGANIZER_PUBLIC_URL?.trim() ?: ''}",
                        "ORGANIZER_TIME_ZONE=${timeZone}",
                        "ORGANIZER_MACHINE_ID=${machineId}",
                    ]
                    withEnv(settings) {
                        def start = {
                            bat 'docker run --detach --name organizer --restart unless-stopped ' +
                                '--publish %ORGANIZER_PORT%:%ORGANIZER_PORT% --volume organizer-data:/data ' +
                                '--env ORGANIZER_PORT --env ORGANIZER_PUBLIC_URL --env ORGANIZER_MACHINE_ID --env ORGANIZER_PASSWORD ' +
                                '--env TZ=%ORGANIZER_TIME_ZONE% organizer'
                        }
                        if (passwordId) {
                            withCredentials([string(credentialsId: passwordId, variable: 'ORGANIZER_PASSWORD')]) {
                                start()
                            }
                        } else {
                            start()
                        }

                        try {
                            timeout(time: 90, unit: 'SECONDS') {
                                waitUntil {
                                    // 000 while it isn't listening yet; 401 means it's up and asking for the password.
                                    def status = bat(returnStdout: true,
                                        script: '@curl -s -o nul -w "%%{http_code}" http://127.0.0.1:%ORGANIZER_PORT%/ & exit /b 0').trim()
                                    status in ['200', '401']
                                }
                            }
                        } catch (e) {
                            bat 'docker logs --tail 40 organizer'
                            error "Organizer didn't answer on port ${port} within 90 seconds."
                        }
                    }

                    // Images left without a tag by earlier deploys.
                    bat 'docker image prune --force --filter label=org.opencontainers.image.title=organizer'
                    echo "Organizer is running: http://localhost:${port} (logs: docker logs organizer)"
                }
            }
        }
    }
}
