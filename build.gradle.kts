plugins {
    `java-library`
    `maven-publish`
    jacoco
    id("com.gradleup.shadow") version "9.0.2"
    id("com.github.spotbugs") version "6.0.26"
}

group = "io.paradaux"
version = providers.gradleProperty("version")
    .orElse("1.2.0-SNAPSHOT")
    .get()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
//    withJavadocJar()
}

tasks.withType<Jar>().configureEach {
    // Reproducible builds
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper & MC-Specific dependencies
    // Kept in step with the consumer plugins' Paper line (gradle/libs.versions.toml in the monorepo).
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    // DI — api, not implementation: the public surface exposes Guice types
    // (HiberniaModule extends AbstractModule, CommandManager takes an Injector).
    api("com.google.inject:guice:7.0.0")
    implementation("com.google.guava:guava:33.2.1-jre")

    // Configurator
    implementation("org.reflections:reflections:0.10.2")

    // Lombok
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")
    testCompileOnly("org.projectlombok:lombok:1.18.34")
    testAnnotationProcessor("org.projectlombok:lombok:1.18.34")

    // Testing
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    finalizedBy(tasks.jacocoTestReport)
}

jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
}

// Coverage gate (ADT-63). A no-regression floor wired into `check`, ratcheted up as the
// per-area coverage work lands. The Paper-coupled renderer needs a running server to
// exercise, so it is the one documented exclusion — every renderer-agnostic class stays in scope.
tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    // The Paper-coupled renderer needs a running server, so drop it from the analysed
    // classes (the same way Treasury excludes its Bukkit glue from the gate).
    classDirectories.setFrom(
        files(classDirectories.files.map {
            fileTree(it) { exclude("io/paradaux/hibernia/framework/usher/render/PaperDialogRenderer*") }
        })
    )
    violationRules {
        rule {
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.95".toBigDecimal()
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = "0.83".toBigDecimal()
            }
        }
    }
}
tasks.check { dependsOn(tasks.jacocoTestCoverageVerification) }

// Static analysis (ADT-61). Non-failing to begin with — the value is the report; ratchet to
// fail-on-new once the existing findings are triaged.
spotbugs {
    ignoreFailures.set(true)
    showStackTraces.set(false)
}
tasks.withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    reports.create("html") { required.set(true) }
    reports.create("xml") { required.set(false) }
}

/**
 * Optional: keep a shadowJar for your *local* testing,
 * but do NOT publish it. No relocations here — consumers handle that.
 */
tasks.shadowJar {
    archiveClassifier.set("shaded")
    // If you want to inspect a fat jar locally, you can minimize it — not published anyway.
    // minimize()
    // No relocations here — consumers will provide plugin-private namespaces.
}

// Ensure the plain jar remains the main artifact
tasks.jar { enabled = true }

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])     // includes sources because of withSourcesJar()
            // DO NOT add: artifact(tasks.named("sourcesJar"))

            groupId = project.group.toString()
            artifactId = "hibernia-framework"
            version = project.version.toString()

            pom {
                name.set("hibernia-framework")
                description.set("Common core for Paradaux Minecraft plugins: Guice bootstrap + config/commands/events abstractions.")
                url.set("https://repo.paradaux.io")
                licenses {
                    license {
                        name.set("AGPL-3.0-or-later")
                        url.set("https://www.gnu.org/licenses/agpl-3.0.en.html")
                        distribution.set("repo")
                    }
                }
                developers { developer { id.set("rian"); name.set("Rían Errity") } }
                scm {
                    url.set("https://github.com/ParadauxIO/hibernia-framework")
                    connection.set("scm:git:https://github.com/ParadauxIO/hibernia-framework.git")
                    developerConnection.set("scm:git:ssh://git@github.com/ParadauxIO/hibernia-framework.git")
                }
            }
        }
    }
    repositories {
        val isSnapshot = version.toString().endsWith("-SNAPSHOT")
        maven {
            name = if (isSnapshot) "ReposiliteSnapshots" else "ReposiliteReleases"
            url = uri(if (isSnapshot) "https://repo.paradaux.io/snapshots" else "https://repo.paradaux.io/releases")
            credentials {
                username = System.getenv("REPO_USER")
                password = System.getenv("REPO_PASS")
            }
        }
    }
}