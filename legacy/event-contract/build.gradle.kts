plugins {
    `java-library`
}

dependencies {
    api(platform(libs.spring.boot.dependencies))
    implementation(platform(libs.cloudevents.bom))
    implementation(libs.cloudevents.json.jackson)
    implementation(libs.jackson2.jsr310)
    implementation(libs.json.schema.validator)

    testImplementation(libs.spring.boot.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        resources.srcDir(rootProject.file("contracts/v1"))
    }
}
