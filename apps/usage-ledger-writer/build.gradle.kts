plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.actuator)
    testImplementation(libs.spring.boot.test)
}
