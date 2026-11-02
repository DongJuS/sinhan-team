pipeline {
    agent {
        node {
            label 'built-in'
            customWorkspace '/workspace/sinhan-team'
        }
    }
    options {
        disableConcurrentBuilds()
        timestamps()
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20'))
        skipDefaultCheckout(true)
    }
    triggers { pollSCM('H/5 * * * *') }
    parameters {
        booleanParam(name: 'DEPLOY', defaultValue: true, description: 'Deploy the tested main commit to the isolated local demo environment.')
    }
    stages {
        stage('Checkout main') {
            steps {
                checkout scm
                sh 'mkdir -p .artifacts .ci-cache'
                script { env.RELEASE_SHA = sh(script: 'git rev-parse HEAD', returnStdout: true).trim() }
            }
        }
        stage('Java, Python and API checks') {
            steps { sh './scripts/ci-unit.sh' }
            post {
                always { junit allowEmptyResults: true, testResults: 'services/**/target/surefire-reports/*.xml,.artifacts/python-tests.xml' }
            }
        }
        stage('Container images') {
            steps { sh 'docker compose build' }
        }
        stage('Integration and crash recovery') {
            steps { sh './scripts/test-integration.sh' }
        }
        stage('Tag verified release') {
            steps { sh './scripts/tag-release.sh' }
        }
        stage('Deploy local demo') {
            when { expression { params.DEPLOY } }
            steps { sh './scripts/deploy-jenkins.sh' }
        }
    }
    post {
        always {
            sh 'docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml logs --no-color --tail 100 > .artifacts/integration-services.log 2>&1 || true'
            sh 'docker compose -p sinhan-food-test -f compose.yaml -f compose.test.yaml down --volumes --remove-orphans || true'
            archiveArtifacts allowEmptyArchive: true, artifacts: '.artifacts/*.log,.artifacts/*release*.json,.artifacts/*tests.xml'
        }
    }
}
