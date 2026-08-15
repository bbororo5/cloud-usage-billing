plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":libs:event-contract"))
    implementation(libs.spring.boot.actuator)
    implementation(libs.spring.boot.jdbc)
    implementation(libs.spring.boot.kafka)
    runtimeOnly(libs.clickhouse.jdbc)
    testImplementation(libs.spring.boot.test)
}
