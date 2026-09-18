plugins { java }

val accessTest by sourceSets.creating

dependencies {
    "accessTestImplementation"(platform(libs.spring.boot.dependencies))
    "accessTestImplementation"(libs.spring.boot.test)
    "accessTestImplementation"(libs.postgresql)
    "accessTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

tasks.register<Test>("accessTest") {
    description = "Checks actual PostgreSQL application logins in a disposable database."
    testClassesDirs = accessTest.output.classesDirs
    classpath = accessTest.runtimeClasspath
    outputs.upToDateWhen { false }
    doFirst {
        require(!System.getenv("BILLING_ACCESS_TEST_URL").isNullOrBlank()) {
            "Run bash scripts/verify-postgresql-access.sh to provision the isolated database."
        }
    }
}
