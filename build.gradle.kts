@file:Suppress("SpellCheckingInspection")

import org.jetbrains.intellij.tasks.RunPluginVerifierTask.FailureLevel
import java.util.*

plugins {
	id("org.jetbrains.intellij") version "0.6.5"
	java
}

group = "net.sf.opk"
version = "0.2.1-SNAPSHOT"

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.apache.avro", "avro", "1.10.1").exclude("org.slf4j")
	testImplementation("junit", "junit", "4.12")
	testImplementation("org.assertj", "assertj-core", "3.18.1")
}

// See https://github.com/JetBrains/gradle-intellij-plugin/
intellij {
	version = "2020.2"
	setPlugins("com.intellij.java", "PsiViewer:202-SNAPSHOT.3") // Add the java plugin here to satisfy test dependencies.
}
tasks.getByName<org.jetbrains.intellij.tasks.PatchPluginXmlTask>("patchPluginXml") {
	version(project.version)
	sinceBuild("202")
	untilBuild("203.*")
	changeNotes(
		"""
		<p>Version ???:</p>
		<ul>
		<li>Add Avro Schema and Avro Protocol languages, allowing language injection</li>
		</ul>
		<p>Version 0.2.0:</p>
		<ul>
		<li>Add support for imports</li>
		<li>Add file types and JSON schemas for <code>.avsc</code> &amp; <code>.avpr</code></li>
		</ul>
		<p>Version 0.1.1:</p>
		<ul>
		<li>Extra build for IntelliJ 2020.3</li>
		</ul>
		<p>Version 0.1.0:</p>
        <ul>
		<li>Initial release</li>
		<li>Full parsing of Avro .avdl files, based on Avro 1.10 syntax</li>
		<li>Syntax highlighting & formatting</li>
		<li>Code completion based on syntax and supported references</li>
		<li>Some semantic checks</li>
		<li>Some refactoring support (renaming & deleting named types)</li>
        </ul>
		"""
	)
}
tasks.getByName<org.jetbrains.intellij.tasks.RunPluginVerifierTask>("runPluginVerifier") {
	ideVersions(listOf("2020.2.4", "2020.3", "2020.3.2"))

	failureLevel = EnumSet.complementOf(EnumSet.of(FailureLevel.DEPRECATED_API_USAGES))
}

sourceSets["main"].java.srcDirs("src/main/gen")
