plugins {
    id("java")
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.wavjaby"
version = "0.0.1-SNAPSHOT"

mavenPublishing {
  coordinates("com.wavjaby", "easyjdbc", "0.0.1-SNAPSHOT")

  pom {
    name.set("EasyJDBC")
    description.set("A high-performance, annotation-based JDBC framework for Java that generates repository implementations at compile time.")
    inceptionYear.set("2024")
    url.set("https://github.com/WavJaby/EasyJDBC/")
    licenses {
      license {
        name.set("The Apache License, Version 2.0")
        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
        distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
      }
    }
    developers {
      developer {
        id.set("wavjaby")
        name.set("WavJaby")
        url.set("https://github.com/WavJaby/")
      }
    }
    scm {
      url.set("https://github.com/WavJaby/EasyJDBC/")
      connection.set("scm:git:git://github.com/WavJaby/EasyJDBC.git")
      developerConnection.set("scm:git:ssh://git@github.com/WavJaby/EasyJDBC.git")
    }
  }

  publishToMavenCentral()
  signAllPublications()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
}

repositories {
    mavenCentral {
        content { excludeModule("javax.media", "jai_core") }
    }
    maven("https://repo.osgeo.org/repository/release/")
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.0.2"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")

    implementation("com.squareup:javapoet:1.13.0")
}

tasks.jar {
    from("src/main/java") {
        include("com/wavjaby/jdbc/util/*.java")
    }
}
