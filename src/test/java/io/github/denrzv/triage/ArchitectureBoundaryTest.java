package io.github.denrzv.triage;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import io.github.denrzv.triage.domain.port.ClassificationPolicyPort;
import io.github.denrzv.triage.domain.port.DefinitionNotFoundException;
import io.github.denrzv.triage.domain.port.JevClassificationPort;
import io.github.denrzv.triage.domain.port.JevException;
import io.github.denrzv.triage.domain.port.TaxonomyPort;

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

    /**
     * FEATURE-006: the graph may use only the read-only definition ports and the Jev decision port. It has no access
     * to task-management or any other side-effecting port, so it cannot mutate a Service Request.
     */
    @ArchTest
    static final ArchRule graphUsesOnlyAllowedPorts = noClasses()
            .that()
            .resideInAPackage(GRAPH)
            .should()
            .dependOnClassesThat(resideInAPackage(ROOT + "domain.port..")
                    .and(not(belongToAnyOf(
                            JevClassificationPort.class,
                            JevException.class,
                            TaxonomyPort.class,
                            ClassificationPolicyPort.class,
                            DefinitionNotFoundException.class))));
}
