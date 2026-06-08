package com.jorisjonkers.personalstack.common.archunit

import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.library.Architectures.layeredArchitecture
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices

object HexagonalArchitectureRules {
    val NO_CIRCULAR_DEPENDENCIES: ArchRule =
        slices()
            .matching("..(*)..")
            .should()
            .beFreeOfCycles()
            .because("circular dependencies between packages are forbidden (ADR-013 rule 1)")

    val LAYERED_ARCHITECTURE: ArchRule =
        layeredArchitecture()
            .consideringAllDependencies()
            .layer("Web")
            .definedBy("..infrastructure.web..")
            .layer("Application")
            .definedBy("..application..")
            .layer("Domain")
            .definedBy("..domain..")
            .layer("Persistence")
            .definedBy("..infrastructure.persistence..")
            .layer("Messaging")
            .definedBy("..infrastructure.messaging..")
            .layer("Security")
            .definedBy("..infrastructure.security..")
            .layer("Integration")
            .definedBy("..infrastructure.integration..")
            .layer("Config")
            .definedBy("..config..")
            .whereLayer("Web")
            .mayOnlyAccessLayers("Application", "Domain", "Config")
            .whereLayer("Application")
            .mayOnlyAccessLayers("Domain")
            .whereLayer("Persistence")
            .mayOnlyAccessLayers("Domain", "Config")
            .whereLayer("Messaging")
            .mayOnlyAccessLayers("Domain", "Application", "Config")
            .whereLayer("Security")
            .mayOnlyAccessLayers("Domain", "Application", "Config")
            .whereLayer("Integration")
            .mayOnlyAccessLayers("Domain", "Config")
            .whereLayer("Domain")
            .mayNotAccessAnyLayer()
            .because("strict layered architecture must be respected (ADR-006, ADR-013 rule 7)")

    val DOMAIN_MUST_NOT_DEPEND_ON_SPRING: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "org.jooq..",
                "com.fasterxml.jackson..",
            ).because("domain layer must have zero framework dependencies (ADR-006, ADR-013 rule 3)")

    val CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..infrastructure.web..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure.persistence..")
            .because("controllers must go through application layer (ADR-013 rule 2)")

    val NO_FIELD_INJECTION: ArchRule =
        noFields()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .because("constructor injection only — field injection is banned (ADR-012, ADR-013 rule 5)")

    val NAMING_CONTROLLERS: ArchRule =
        classes()
            .that()
            .resideInAPackage("..infrastructure.web..")
            .and()
            .areAnnotatedWith("org.springframework.web.bind.annotation.RestController")
            .should()
            .haveSimpleNameEndingWith("Controller")
            .because("REST controllers must follow *Controller naming convention (ADR-013 rule 4)")

    val NAMING_REPOSITORIES: ArchRule =
        classes()
            .that()
            .resideInAPackage("..infrastructure.persistence..")
            .and()
            .areAnnotatedWith("org.springframework.stereotype.Repository")
            .should()
            .haveSimpleNameContaining("Repository")
            .because("repositories must follow *Repository naming convention (ADR-013 rule 4)")

    val COMMANDS_MUST_NOT_DEPEND_ON_WEB_OR_INFRA: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..application.command..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "..infrastructure..",
            ).because("commands must not import infrastructure code (ADR-013 rule 8)")

    val DOMAIN_MUST_NOT_DEPEND_ON_INFRASTRUCTURE: ArchRule =
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .because("domain objects must not depend on infrastructure (ADR-013 rule 6)")

    val ALL_RULES: List<ArchRule> =
        listOf(
            DOMAIN_MUST_NOT_DEPEND_ON_SPRING,
            CONTROLLERS_MUST_NOT_ACCESS_REPOSITORIES,
            NO_FIELD_INJECTION,
            NAMING_CONTROLLERS,
            NAMING_REPOSITORIES,
            COMMANDS_MUST_NOT_DEPEND_ON_WEB_OR_INFRA,
            DOMAIN_MUST_NOT_DEPEND_ON_INFRASTRUCTURE,
        )
}
