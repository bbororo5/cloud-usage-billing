plugins { java }

dependencies {
    testImplementation(platform(libs.spring.boot.dependencies))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    val rules = rootProject.file("gradle/module-boundaries.gradle.kts")
    inputs.file(rules)
    systemProperty("boundary.rules", rules.absolutePath)
    systemProperty("gradle.installation", gradle.gradleHomeDir!!.absolutePath)
    systemProperty("boundary.testkit", layout.buildDirectory.dir("testkit").get().asFile.absolutePath)
}
