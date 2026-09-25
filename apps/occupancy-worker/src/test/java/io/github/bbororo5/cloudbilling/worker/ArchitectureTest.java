package io.github.bbororo5.cloudbilling.worker;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test void boundaries() {
        var classes = new ClassFileImporter().withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages("io.github.bbororo5.cloudbilling.worker");
        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat().resideOutsideOfPackages("java..", "..domain..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "org.springframework..", "java.sql..", "org.apache.kafka..")
                .allowEmptyShould(true).check(classes);
        noClasses().that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAnyPackage("..application..", "..domain..", "..port..", "..adapter..")
                .check(classes);
        noClasses().that().resideInAPackage("..attribution..")
                .should().dependOnClassesThat().resideInAnyPackage("..occupancyhistory.application..", "..occupancyhistory.domain..",
                        "..occupancyhistory.port..", "..occupancyhistory.adapter..")
                .allowEmptyShould(true).check(classes);
    }
}
