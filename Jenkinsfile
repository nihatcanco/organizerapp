// Builds and tests Organizer, then runs it in Docker on port 8080 of the Windows agent that ran the
// build. The container isn't a child of the build, so it keeps running after the build ends.
// See "Deploying on Windows with Jenkins" in README.md.
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

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Install the build on this agent after the tests pass.')
        string(name: 'PUBLIC_URL', defaultValue: '', description: 'Optional address for QR labels, e.g. http://organizer.lan:8080.')
        choice(name: 'AI', choices: ['on', 'off'], description: '"off" hides Fill in from photo.')
        string(name: 'PASSWORD_CREDENTIALS_ID', defaultValue: '', description: 'Optional: ID of a "Secret text" credential holding the app password.')
    }

    environment {
        APP_PORT = '8080'
    }

    stages {
        stage('Build and test') {
            steps {
                // No daemon: Jenkins ends the build's processes when it finishes anyway.
                bat 'gradlew.bat --no-daemon clean test buildFatJar'
            }
            post {
                always {
                    junit testResults: 'build/test-results/test/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Deploy') {
            when {
                // In a multibranch job only main is deployed; other branches are just built and tested.
                expression { params.DEPLOY && (env.BRANCH_NAME == null || env.BRANCH_NAME == 'main') }
            }
            steps {
                script {
                    bat 'docker build --tag organizer .'
                    if (bat(returnStatus: true, script: '@docker container inspect organizer >nul 2>&1') == 0) {
                        // Gives the app time to close the database before the backup.
                        bat 'docker stop --time 30 organizer && docker rm organizer'
                    }
                    bat 'docker run --rm --volume organizer-data:/data --entrypoint /app/backup.sh organizer'

                    // Settings reach the container as environment variables (--env with a name only), so no
                    // value, the password included, is pasted into a command line.
                    def settings = ["ORGANIZER_PUBLIC_URL=${params.PUBLIC_URL}", "ORGANIZER_AI=${params.AI}"]
                    def start = {
                        withEnv(settings) {
                            bat 'docker run --detach --name organizer --restart unless-stopped ' +
                                '--publish %APP_PORT%:%APP_PORT% --volume organizer-data:/data --env ORGANIZER_PORT=%APP_PORT% ' +
                                '--env ORGANIZER_PUBLIC_URL --env ORGANIZER_AI --env ORGANIZER_PASSWORD organizer'
                        }
                    }
                    if (params.PASSWORD_CREDENTIALS_ID?.trim()) {
                        withCredentials([string(credentialsId: params.PASSWORD_CREDENTIALS_ID.trim(), variable: 'ORGANIZER_PASSWORD')]) {
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
                                    script: '@curl -s -o nul -w "%%{http_code}" http://127.0.0.1:%APP_PORT%/ & exit /b 0').trim()
                                status in ['200', '401']
                            }
                        }
                    } catch (e) {
                        bat 'docker logs --tail 40 organizer'
                        error "Organizer didn't answer on port ${env.APP_PORT} within 90 seconds."
                    }

                    // Images left without a tag by earlier deploys.
                    bat 'docker image prune --force --filter label=org.opencontainers.image.title=organizer'
                    echo "Organizer is running: http://localhost:${env.APP_PORT} (logs: docker logs organizer)"
                }
            }
        }
    }

    post {
        success {
            archiveArtifacts artifacts: 'build/libs/organizer.jar', fingerprint: true
        }
    }
}
