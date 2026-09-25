plugins { alias(libs.plugins.spring.boot) }

dependencies {
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.actuator)
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.jackson2.get()}")
    implementation(libs.json.schema.validator)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.test)
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
    testImplementation("org.testcontainers:kafka:1.21.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main { resources.srcDir(rootProject.file("contracts")) }
tasks.test {
    useJUnitPlatform { excludeTags("integration", "kafka") }
    systemProperty("contracts.dir", rootProject.file("contracts").absolutePath)
}
tasks.register<Test>("integrationTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("integration"); excludeTags("kafka") }
    outputs.upToDateWhen { false }
    systemProperty("contracts.dir", rootProject.file("contracts").absolutePath)
}
tasks.register<Test>("kafkaTest") {
    dependsOn(tasks.named("bootJar"))
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("kafka") }
    outputs.upToDateWhen { false }
    systemProperty("contracts.dir", rootProject.file("contracts").absolutePath)
    systemProperty("worker.jar", layout.buildDirectory.file("libs/occupancy-worker-${project.version}.jar").get().asFile.absolutePath)
}
