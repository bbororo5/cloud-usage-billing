package io.github.bbororo5.cloudbilling.worker;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  @Test
  void onlyApprovalOwnerCanRecordApproval() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.bbororo5.cloudbilling.worker");
    noClasses()
        .that()
        .doNotHaveFullyQualifiedName(
            "io.github.bbororo5.cloudbilling.worker.attribution.application.ApprovalService")
        .should()
        .callMethodWhere(
            new com.tngtech.archunit.base.DescribedPredicate<>("record approval") {
              @Override
              public boolean test(com.tngtech.archunit.core.domain.JavaMethodCall call) {
                return call.getTarget().getName().equals("recordApproval")
                    && call.getTargetOwner().getPackageName().contains(".attribution.");
              }
            })
        .check(classes);
  }

  @Test
  void boundaries() {
    var classes =
        new ClassFileImporter()
            .withImportOption(new ImportOption.DoNotIncludeTests())
            .importPackages("io.github.bbororo5.cloudbilling.worker");
    noClasses()
        .that()
        .resideInAPackage("..domain..")
        .should()
        .dependOnClassesThat()
        .resideOutsideOfPackages("java..", "..domain..")
        .allowEmptyShould(true)
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..application..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..adapter..", "org.springframework..", "java.sql..", "org.apache.kafka..")
        .allowEmptyShould(true)
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..application..", "..domain..", "..port..", "..adapter..")
        .check(classes);
    noClasses()
        .that()
        .resideInAPackage("..attribution..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..occupancyhistory.application..",
            "..occupancyhistory.domain..",
            "..occupancyhistory.port..",
            "..occupancyhistory.adapter..")
        .allowEmptyShould(true)
        .check(classes);
  }
}
