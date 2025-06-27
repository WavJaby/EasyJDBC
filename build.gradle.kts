plugins {
    id("java")
    id("maven-publish")
}

group = "com.wavjaby"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral {
        content { excludeModule("javax.media", "jai_core") }
    }
    maven("https://repo.osgeo.org/repository/release/")
}

dependencies {
    compileOnly(platform("org.springframework.boot:spring-boot-dependencies:3.5.3"))
    compileOnly("org.springframework.boot:spring-boot-starter-data-jdbc")
    
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")

    compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
    annotationProcessor("com.google.auto.service:auto-service:1.1.1")
}

tasks.jar {
    from("src/main/java") {
        include("com/wavjaby/jdbc/util/*.java")
    }
}

