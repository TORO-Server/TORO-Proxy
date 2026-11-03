plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "net.torosaba"
version = "1.4.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.yaml:snakeyaml:2.2")
    compileOnly("com.velocitypowered:velocity-api:4.3.1")

    testImplementation("com.velocitypowered:velocity-api:4.3.1")
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("velocity-plugin.json") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    archiveClassifier.set("thin")
}

// 同じソースからは、どこでビルドしても同じ JAR になるようにする (時刻と順番を固定)
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// Kotlin の標準ライブラリは JAR に入れる。ほかの Kotlin 製プラグインと衝突しないよう、パッケージを移す
tasks.shadowJar {
    archiveBaseName.set("TORO-Proxy")
    archiveClassifier.set("")
    relocate("kotlin", "net.torosaba.proxy.libs.kotlin")
    relocate("org.jetbrains.annotations", "net.torosaba.proxy.libs.annotations")
    relocate("org.intellij.lang.annotations", "net.torosaba.proxy.libs.intellij")
    relocate("org.yaml.snakeyaml", "net.torosaba.proxy.libs.snakeyaml")
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
