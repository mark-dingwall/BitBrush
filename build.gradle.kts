plugins {
	java
	jacoco
	id("org.springframework.boot") version "3.5.11"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "au.com.dingwall.mark.bitbrush"
version = "0.1.0"
description = "Collaborative pixel art canvas"

if (providers.environmentVariable("PIN_CALIBRATION").orNull == "true") {
	gradle.startParameter.maxWorkerCount = 1
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

configurations {
	compileOnly {
		extendsFrom(configurations.annotationProcessor.get())
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springframework.security:spring-security-crypto")
	implementation("org.bouncycastle:bcprov-jdk18on:1.86")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	runtimeOnly("com.h2database:h2")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.flywaydb:flyway-database-postgresql")
	annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation(platform("org.testcontainers:testcontainers-bom:1.21.4"))
	testImplementation("org.testcontainers:junit-jupiter")
	testImplementation("org.testcontainers:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
	systemProperty("spring.profiles.active", "dev")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.named<Test>("test") {
	exclude("**/LegacyIdentityMigrationTest*.class")
	finalizedBy(tasks.jacocoTestReport)
	if (providers.environmentVariable("PIN_CALIBRATION").orNull == "true") {
		maxHeapSize = "128m"
		maxParallelForks = 1
		jvmArgs("-XX:+UseSerialGC")
	}
}

val migrationTest by tasks.registering(Test::class) {
	description = "Verifies legacy identity migrations against PostgreSQL"
	group = "verification"
	dependsOn(tasks.testClasses)
	testClassesDirs = sourceSets.test.get().output.classesDirs
	classpath = sourceSets.test.get().runtimeClasspath
	filter {
		includeTestsMatching("*LegacyIdentityMigrationTest")
		isFailOnNoMatchingTests = true
	}
	useJUnitPlatform()
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		csv.required = true
	}
}
