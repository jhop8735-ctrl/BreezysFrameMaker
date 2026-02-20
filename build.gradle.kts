plugins {
    id("java")
    `maven-publish`
}

group = "net.botwithus.scripts"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        setUrl("https://nexus.botwithus.net/repository/maven-releases/")
    }
}

configurations {
    create("includeInJar") {
        this.isTransitive = false
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
    options.release.set(20)
}

dependencies {
    implementation("net.botwithus.rs3:botwithus-api:1.+")
    implementation("net.botwithus.xapi.public:api:1.+")
    "includeInJar"("net.botwithus.xapi.public:api:1.+")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
}

val copyJar by tasks.register<Copy>("copyJar") {
    from("build/libs/")
    into(file(System.getProperty("user.home")).resolve("BotWithUs/scripts/local/"))
    include("*.jar")
}

tasks.named<Jar>("jar") {
    from({
        configurations["includeInJar"].map { zipTree(it) }
    })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveFileName.set("BreezysFrameMaker.jar")
    finalizedBy(copyJar)
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
