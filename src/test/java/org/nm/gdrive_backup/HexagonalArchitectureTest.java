package org.nm.gdrive_backup;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.nm.gdrive_backup.domain.port.out.DriveReadPort;
import org.nm.gdrive_backup.domain.port.out.GoogleOAuthPort;
import org.nm.gdrive_backup.domain.port.out.ServiceAccountCredentialPort;
import org.nm.gdrive_backup.domain.port.out.WorkspaceUserDirectoryPort;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@AnalyzeClasses(packages = "org.nm.gdrive_backup", importOptions = DoNotIncludeTests.class)
class HexagonalArchitectureTest {

	@ArchTest
	static final ArchRule domain_has_a_domain_package = classes()
			.that().resideInAnyPackage("..domain..")
			.should().resideInAnyPackage(
				"..domain.model..",
				"..domain.port.in..",
				"..domain.port.out..",
				"..domain.service..");

	@ArchTest
	static final ArchRule root_package_contains_only_application_entry_points = classes()
			.that().resideInAPackage("org.nm.gdrive_backup")
			.and().areTopLevelClasses()
			.should().haveSimpleNameEndingWith("Application");

	@ArchTest
	static final ArchRule domain_does_not_depend_on_adapters_or_configuration = noClasses()
			.that().resideInAnyPackage("..domain..")
			.should().dependOnClassesThat()
			.resideInAnyPackage("..adapter..", "..configuration..");

	@ArchTest
	static final ArchRule domain_does_not_depend_on_frameworks_or_external_apis = noClasses()
			.that().resideInAnyPackage("..domain..")
			.should().dependOnClassesThat()
			.resideInAnyPackage(
				"org.springframework..",
				"javafx..",
				"com.google..",
				"jakarta.persistence..",
				"javax.persistence..");

	@ArchTest
	static final ArchRule adapters_do_not_depend_on_application_configuration = noClasses()
			.that().resideInAnyPackage("..adapter..")
			.should().dependOnClassesThat()
			.resideInAnyPackage("org.nm.gdrive_backup.configuration..");

	@ArchTest
	static final ArchRule inbound_ports_are_interfaces_with_conventional_names = classes()
			.that().resideInAnyPackage("..domain.port.in..")
			.should().beInterfaces()
			.andShould().haveSimpleNameEndingWith("UseCase")
			.orShould().haveSimpleNameEndingWith("Approval");

	@ArchTest
	static final ArchRule outbound_ports_are_interfaces_named_as_ports = classes()
			.that().resideInAnyPackage("..domain.port.out..")
			.should().beInterfaces()
			.andShould().haveSimpleNameEndingWith("Port");

	@ArchTest
	static final ArchRule drive_adapters_are_named_as_adapters = classes()
			.that().resideInAnyPackage("..adapter..")
			.and().implement(DriveReadPort.class)
			.should().haveSimpleNameEndingWith("Adapter");

	@ArchTest
	static final ArchRule oauth_adapters_are_named_as_adapters = classes()
			.that().resideInAnyPackage("..adapter..")
			.and().implement(GoogleOAuthPort.class)
			.should().haveSimpleNameEndingWith("Adapter");

	@ArchTest
	static final ArchRule credential_adapters_are_named_as_adapters = classes()
			.that().resideInAnyPackage("..adapter..")
			.and().implement(ServiceAccountCredentialPort.class)
			.should().haveSimpleNameEndingWith("Adapter");

	@ArchTest
	static final ArchRule workspace_directory_adapters_are_named_as_adapters = classes()
			.that().resideInAnyPackage("..adapter..")
			.and().implement(WorkspaceUserDirectoryPort.class)
			.should().haveSimpleNameEndingWith("Adapter");

	@ArchTest
	static final ArchRule spring_configuration_classes_have_conventional_names = classes()
			.that().areAnnotatedWith(Configuration.class)
			.should().haveSimpleNameEndingWith("Configuration");

	@ArchTest
	static final ArchRule spring_property_classes_have_conventional_names = classes()
			.that().areAnnotatedWith(ConfigurationProperties.class)
			.should().haveSimpleNameEndingWith("Properties");
}