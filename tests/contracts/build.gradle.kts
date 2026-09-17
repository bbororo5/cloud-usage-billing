plugins {
    java
}

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.json.schema.validator)
    testImplementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:${libs.versions.jackson2.get()}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    inputs.dir(rootProject.file("contracts"))
    systemProperty("contracts.dir", rootProject.file("contracts").absolutePath)
}
