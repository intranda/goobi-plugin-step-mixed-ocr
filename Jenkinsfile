pipeline {
  agent {
    docker {
      image 'maven:3-jdk-8'
      args '-v $HOME/.m2/root/.m2'
    }
  }

  stages {
    stage('build') {
      steps {
        sh 'mvn -f pom.xml package'
      }
    }
  }
  post {
    success {
      archiveArtifacts artifacts: 'target/plugin_intranda_step_mixedocr.jar,doc/*', fingerprint:
      true
    }
  }
}