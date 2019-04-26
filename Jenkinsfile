pipeline {
  agent {
    docker {
      image 'maven:3-jdk-8'
      args '-v $HOME/.m2:/var/maven/.m2:z -u 1000 -ti -e _JAVA_OPTIONS=-Duser.home=/var/maven -e MAVEN_CONFIG=/var/maven/.m2'
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
      archiveArtifacts artifacts: '**/target/*.jar, */plugin_*.xml, plugin_*.xml', fingerprint: true, onlyIfSuccessful: true
      true
    }
  }
}