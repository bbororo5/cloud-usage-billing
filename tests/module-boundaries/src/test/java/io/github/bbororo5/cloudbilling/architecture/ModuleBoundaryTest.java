package io.github.bbororo5.cloudbilling.architecture;

import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(120)
class ModuleBoundaryTest {
    @TempDir Path fixture;

    private GradleRunner build(String declarations, String task) throws IOException {
        // A disposable build applies the real rule script, not a reimplementation.
        for (String module : new String[]{"apps/billing-bff", "apps/settlement-batch",
                "libs/event-contract", "libs/shared", "tests/fixtures"}) {
            Files.createDirectories(fixture.resolve(module));
        }
        Files.writeString(fixture.resolve("settings.gradle"), """
            rootProject.name = 'boundary-fixture'
            include 'apps:billing-bff', 'apps:settlement-batch', 'libs:event-contract', 'libs:shared', 'tests:fixtures'
            """);
        Files.copy(Path.of(System.getProperty("boundary.rules")), fixture.resolve("module-boundaries.gradle.kts"));
        Files.writeString(fixture.resolve("build.gradle"), """
            allprojects { apply plugin: 'java-library' }
            apply from: 'module-boundaries.gradle.kts'
            """ + declarations);
        return GradleRunner.create()
            .withProjectDir(fixture.toFile())
            .withGradleInstallation(new File(System.getProperty("gradle.installation")))
            .withTestKitDir(new File(System.getProperty("boundary.testkit")))
            .withArguments(task, "--offline", "--stacktrace", "--console=plain");
    }

    private String dependency(String source, String configuration, String target) {
        return "project('" + source + "') { dependencies { " + configuration + " project('" + target + "') } }\n";
    }

    @ParameterizedTest(name = "allow {0} {1} -> {2}")
    @CsvSource({
        ":apps:billing-bff, implementation, :libs:event-contract",
        ":libs:event-contract, api, :libs:shared",
        ":tests:fixtures, implementation, :apps:billing-bff",
        ":apps:billing-bff, testImplementation, :tests:fixtures"
    })
    void permitsAllowedDependencies(String source, String configuration, String target) throws IOException {
        var result = build(dependency(source, configuration, target), "verifyModuleBoundaries").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":verifyModuleBoundaries").getOutcome());
        assertTrue(result.getOutput().contains("Module boundary verification passed"));
    }

    @ParameterizedTest(name = "reject {0} {1} -> {2}")
    @CsvSource({
        ":apps:billing-bff, implementation, :apps:settlement-batch, APP_TO_APP",
        ":apps:settlement-batch, runtimeOnly, :apps:billing-bff, APP_TO_APP",
        ":libs:event-contract, api, :apps:billing-bff, LIB_TO_APP",
        ":apps:billing-bff, compileOnly, :tests:fixtures, PRODUCT_TO_TEST",
        ":libs:event-contract, annotationProcessor, :tests:fixtures, PRODUCT_TO_TEST",
        ":libs:event-contract, runtimeOnly, :tests:fixtures, PRODUCT_TO_TEST"
    })
    void rejectsForbiddenDependencies(String source, String configuration, String target, String rule) throws IOException {
        var result = build(dependency(source, configuration, target), "verifyModuleBoundaries").buildAndFail();
        assertEquals(TaskOutcome.FAILED, result.task(":verifyModuleBoundaries").getOutcome());
        assertTrue(result.getOutput().contains(rule), result.getOutput());
        assertTrue(result.getOutput().contains(source + " -> " + target), result.getOutput());
    }

    @Test
    void detectsInheritedCustomConfiguration() throws IOException {
        var result = build("""
            project(':apps:billing-bff') {
                configurations { internalBridge }
                configurations.implementation.extendsFrom(configurations.internalBridge)
                dependencies { internalBridge project(':apps:settlement-batch') }
            }
            """, "verifyModuleBoundaries").buildAndFail();
        assertTrue(result.getOutput().contains("APP_TO_APP: :apps:billing-bff -> :apps:settlement-batch"));
    }

    @Test
    void detectsLibraryBridgeInsteadOfOnlyCheckingApplicationDeclarations() throws IOException {
        var declarations = dependency(":apps:billing-bff", "implementation", ":libs:event-contract")
            + dependency(":libs:event-contract", "implementation", ":libs:shared")
            + dependency(":libs:shared", "implementation", ":apps:settlement-batch");
        var result = build(declarations, "verifyModuleBoundaries").buildAndFail();
        assertTrue(result.getOutput().contains("LIB_TO_APP: :libs:shared -> :apps:settlement-batch"));
    }

    @Test
    void checkTaskCannotOmitBoundaryVerification() throws IOException {
        var result = build(dependency(":apps:billing-bff", "implementation", ":apps:settlement-batch"),
            ":apps:billing-bff:check").buildAndFail();
        assertNotNull(result.task(":verifyModuleBoundaries"));
        assertEquals(TaskOutcome.FAILED, result.task(":verifyModuleBoundaries").getOutcome());
    }
}
