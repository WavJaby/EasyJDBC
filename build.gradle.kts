plugins {
    id("java")
    id("java-library")
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "com.wavjaby"
version = "0.0.2-SNAPSHOT"

mavenPublishing {
  coordinates(group as String, "easyjdbc", version as String)

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

    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // ProcessorUtil.copyUtilityClasses reads com/wavjaby/jdbc/util/*.java off its own classloader (the jar ships those
    // sources). javac's processor loader delegates to the app loader first, so the directory has to be here too.
    classpath += files("src/main/java")
    // The golden tests run javac in-process. The fixture compilation needs the same classpath the tests run on, and
    // the processor copies com/wavjaby/jdbc/util/*.java as *resources*, which only exist in the source tree.
    systemProperty("golden.classpath", sourceSets.test.get().runtimeClasspath.asPath)
    systemProperty("golden.sourceDir", file("src/main/java").absolutePath)
    systemProperty("golden.resourceDir", file("src/test/resources").absolutePath)
    systemProperty("golden.workDir", layout.buildDirectory.dir("golden-work").get().asFile.absolutePath)
    // -Dgolden.update=true rewrites every snapshot instead of comparing
    systemProperty("golden.update", providers.systemProperty("golden.update").getOrElse("false"))
    testLogging { showStandardStreams = true }
}

tasks.jar {
    from("src/main/java") {
        include("com/wavjaby/jdbc/util/*.java")
    }
}
