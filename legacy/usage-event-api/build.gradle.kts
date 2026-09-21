plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":libs:event-contract"))
    implementation(libs.spring.boot.web)
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.kafka)
    implementation(libs.uuid.creator)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.test)
}
