plugins {
    id("java")
    id("maven-publish")
}

group = "com.wavjaby"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral {
        content { excludeModule("javax.media", "jai_core") }
    }
    maven("https://repo.osgeo.org/repository/release/")
}

configurations {
    all {
        exclude(module = "spring-boot-starter-logging")
    }
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.3"))

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-log4j2")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    compileOnly(project(":"))
    annotationProcessor(project(":"))

    // H2 Database
    runtimeOnly("com.h2database:h2:2.2.224")
    compileOnly("com.google.code.findbugs:jsr305:3.0.2")
}

tasks.test {
    maxHeapSize = "8G"
    useJUnitPlatform()
}