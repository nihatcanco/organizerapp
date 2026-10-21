// Builds and tests Organizer, then installs it on the Windows agent that ran the build.
// The app runs as a scheduled task (deploy/windows/deploy.ps1), not as a child of the build,
// so it keeps running after the build ends. See "Deploying on Windows with Jenkins" in README.md.
pipeline {
    agent { label 'windows' }

    options {
        buildDiscarder(logRotator(numToKeepStr: '20'))
        disableConcurrentBuilds()
        timeout(time: 30, unit: 'MINUTES')
    }

    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Install the build on this agent after the tests pass.')
        string(name: 'DEPLOY_DIR', defaultValue: 'C:\\Organizer', description: 'Where the app and its data live. Data is kept between deploys.')
        string(name: 'PORT', defaultValue: '8080', description: 'Port the app listens on.')
        string(name: 'PUBLIC_URL', defaultValue: '', description: 'Optional address for QR labels, e.g. http://organizer.lan:8080.')
        choice(name: 'AI', choices: ['on', 'off'], description: '"off" hides Fill in from photo.')
        string(name: 'PASSWORD_CREDENTIALS_ID', defaultValue: '', description: 'Optional: ID of a "Secret text" credential holding the app password.')
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
                    // Parameters go in as environment variables, so no value is pasted into the script text.
                    def settings = [
                        "DEPLOY_DIR=${params.DEPLOY_DIR}",
                        "PORT=${params.PORT}",
                        "PUBLIC_URL=${params.PUBLIC_URL}",
                        "AI=${params.AI}",
                    ]
                    def deploy = {
                        withEnv(settings) {
                            powershell '''
                                & .\\deploy\\windows\\deploy.ps1 -Jar build\\libs\\organizer.jar `
                                    -DeployDir $env:DEPLOY_DIR -Port $env:PORT -PublicUrl "$env:PUBLIC_URL" -AI $env:AI
                            '''
                        }
                    }
                    if (params.PASSWORD_CREDENTIALS_ID?.trim()) {
                        withCredentials([string(credentialsId: params.PASSWORD_CREDENTIALS_ID.trim(), variable: 'ORGANIZER_PASSWORD')]) {
                            deploy()
                        }
                    } else {
                        deploy()
                    }
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
