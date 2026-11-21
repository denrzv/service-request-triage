package io.github.denrzv.triage;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Enforces the package dependency direction from docs/architecture.md. */
@AnalyzeClasses(packages = "io.github.denrzv.triage", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {

    private static final String ROOT = "io.github.denrzv.triage.";
    private static final String API = ROOT + "api..";
    private static final String APPLICATION = ROOT + "application..";
    private static final String GRAPH = ROOT + "graph..";
    private static final String INFRASTRUCTURE = ROOT + "infrastructure..";

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that()
            .resideInAPackage(ROOT + "domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(API, APPLICATION, GRAPH, INFRASTRUCTURE)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationDoesNotUseOuterLayers = noClasses()
            .that()
            .resideInAPackage(APPLICATION)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(API, GRAPH, INFRASTRUCTURE)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule graphDoesNotUseApiOrInfrastructure = noClasses()
            .that()
            .resideInAPackage(GRAPH)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(API, INFRASTRUCTURE)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule apiDoesNotUseInfrastructureOrGraph = noClasses()
            .that()
            .resideInAPackage(API)
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(INFRASTRUCTURE, GRAPH)
            .allowEmptyShould(true);
}
