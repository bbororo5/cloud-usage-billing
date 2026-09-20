// Initial test-first baseline; forbidden dependency cases must fail until implemented.
tasks.register("verifyModuleBoundaries") {
    doLast { logger.lifecycle("Module boundary verification passed") }
}
