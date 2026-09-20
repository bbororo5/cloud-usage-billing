import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.tasks.SourceSetContainer

abstract class VerifyModuleBoundaries : DefaultTask() {
    @get:Input
    abstract val dependencyEdges: ListProperty<String>

    @TaskAction
    fun verify() {
        val violations = dependencyEdges.get().mapNotNull { edge ->
            val (source, configuration, target) = edge.split('|')
            val rule = when {
                source.startsWith(":apps:") && target.startsWith(":apps:") -> "APP_TO_APP"
                source.startsWith(":libs:") && target.startsWith(":apps:") -> "LIB_TO_APP"
                target.startsWith(":tests:") -> "PRODUCT_TO_TEST"
                else -> null
            }
            rule?.let { "$it: $source -> $target [$configuration]" }
        }
        if (violations.isNotEmpty()) {
            throw GradleException("Module boundary violations:\n" + violations.joinToString("\n"))
        }
        logger.lifecycle("Module boundary verification passed")
    }
}

val boundaryCheck = tasks.register<VerifyModuleBoundaries>("verifyModuleBoundaries") {
    group = "verification"
    description = "Checks approved application, library and test module dependency boundaries."
}

// Inspect Gradle's evaluated model (including inherited configurations), not build-file text.
// No external artifacts are resolved. Only serializable edges reach task execution.
gradle.projectsEvaluated {
    val edges = rootProject.allprojects
        .filter { it.path.startsWith(":apps:") || it.path.startsWith(":libs:") }
        .flatMap { module ->
            val main = module.extensions.findByType(SourceSetContainer::class.java)?.findByName("main")
            if (main == null) emptyList() else {
                listOf(main.compileClasspathConfigurationName, main.runtimeClasspathConfigurationName,
                    main.annotationProcessorConfigurationName).flatMap { configuration ->
                    module.configurations.getByName(configuration).allDependencies
                        .withType(ProjectDependency::class.java)
                        .map { "${module.path}|$configuration|${it.path}" }
                }
            }
        }.distinct().sorted()
    boundaryCheck.configure { dependencyEdges.set(edges) }
}

allprojects {
    pluginManager.withPlugin("java") {
        tasks.named("check") { dependsOn(boundaryCheck) }
    }
}
