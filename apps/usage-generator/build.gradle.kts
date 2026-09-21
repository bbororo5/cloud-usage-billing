plugins { application }
application { mainClass.set("io.github.bbororo5.cloudbilling.generator.GeneratorMain") }
dependencies {
    implementation(platform(libs.spring.boot.dependencies))
    implementation("org.apache.kafka:kafka-clients")
    implementation("com.fasterxml.jackson.core:jackson-databind:${libs.versions.jackson2.get()}")
    runtimeOnly("org.slf4j:slf4j-simple")
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.json.schema.validator)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.test { systemProperty("contracts.dir", rootProject.file("contracts").absolutePath) }
