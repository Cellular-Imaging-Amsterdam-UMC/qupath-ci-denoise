plugins {
    java
}

group = "nl.amsterdamumc"
version = file("version.txt").readText().trim()

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/repositories/releases")
}

val qupathAppDir = providers.gradleProperty("qupathAppDir")
dependencies {
    if (qupathAppDir.isPresent) {
        compileOnly(fileTree(qupathAppDir.get()) { include("*.jar") })
    } else {
        compileOnly("io.github.qupath:qupath-gui-fx:0.7.0")
        compileOnly("io.github.qupath:qupath-extension-bioformats:0.7.0")
        compileOnly("org.bytedeco:opencv:4.11.0-1.5.12")
        compileOnly("net.java.dev.jna:jna:5.17.0")
        compileOnly("org.slf4j:slf4j-api:2.0.17")
        // JavaFX classes are platform-independent. QuPath supplies its own native runtime.
        for (module in listOf("base", "graphics", "controls")) {
            compileOnly("org.openjfx:javafx-$module:25.0.2:linux")
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.jar {
    manifest.attributes(
        "Automatic-Module-Name" to "nl.amsterdamumc.qupath.extension.cidenoise",
        "Implementation-Version" to project.version
    )
    from(listOf("LICENSE", "THIRD_PARTY.md")) { into("META-INF/licenses/cidenoise") }
    from("licenses") { into("META-INF/licenses/cidenoise") }
}
