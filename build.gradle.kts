@file:kotlin.Suppress("unsupported")
import org.gradle.api.JavaVersion.VERSION_17
import org.jetbrains.grammarkit.tasks.GenerateLexerTask
import org.jetbrains.grammarkit.tasks.GenerateParserTask
import org.jetbrains.intellij.tasks.PatchPluginXmlTask
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.*
import java.nio.file.Paths

val isCI = !System.getenv("CI").isNullOrBlank()
val commitHash = kotlin.run {
	val process: Process = Runtime.getRuntime().exec("git rev-parse --short HEAD")
	process.waitFor()
	val output = process.inputStream.use {
		process.inputStream.use { it.readBytes().let(::String) }
	}
	process.destroy()
	output.trim()
}

val pluginComingVersion = "0.4.2"
val pluginVersion = if (isCI) "$pluginComingVersion-$commitHash" else pluginComingVersion
val packageName = "org.ice1000.julia"

group = packageName
version = pluginVersion

plugins {
	java
	id("org.jetbrains.intellij") version "1.11.0"
	// id("org.jetbrains.grammarkit") version "2022.3" <- TODO: Not working
	id("org.jetbrains.grammarkit") version "2021.2.2"
	kotlin("jvm") version "1.3.60"
}

fun fromToolbox(root: String, ide: String) = file(root)
	.resolve(ide)
	.takeIf { it.exists() }
	?.resolve("ch-0")
	?.listFiles()
	.orEmpty()
	.filterNotNull()
	.filter { it.isDirectory }
	.filterNot { it.name.endsWith(".plugins") }
	.maxBy {
		val (major, minor, patch) = it.name.split('.')
		String.format("%5s%5s%5s", major, minor, patch)
	}
	?.also { println("Picked: $it") }

allprojects {
	apply { plugin("org.jetbrains.grammarkit") }
}

//grammarKit {
//	grammarKitRelease = "7aecfcd72619e9c241866578e8312f339b4ddbd8"
//}

intellij {
	updateSinceUntilBuild.set(false)
	instrumentCode.set(true)
	version.set("2022.3")
	if (!isCI) {
		plugins.set(listOf("PsiViewer:223-SNAPSHOT", "java"))
		downloadSources.set(true)
	} else {
		plugins.set(listOf("java"))
	}
	val user = System.getProperty("user.name")
	val os = System.getProperty("os.name")
	val root = when {
		os.startsWith("Windows") -> "C:\\Users\\$user\\AppData\\Local\\JetBrains\\Toolbox\\apps"
		os == "Linux" -> "/home/$user/.local/share/JetBrains/Toolbox/apps"
		else -> return@intellij
	}
	val intellijPath = ["IDEA-C", "IDEA-U"]
		.mapNotNull { fromToolbox(root, it) }.firstOrNull()
	intellijPath?.absolutePath?.let { localPath.set(it) }
	/* val pycharmPath = ["PyCharm-C", "IDEA-C", "IDEA-U"]
		.mapNotNull { fromToolbox(root, it) }.firstOrNull()
	pycharmPath?.absolutePath?.let { alternativeIdePath.set(it) } */
}

java {
	sourceCompatibility = JavaVersion.VERSION_11
	targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<PatchPluginXmlTask>().configureEach {
	changeNotes.set(file("docs/change-notes.html").readText())
	pluginDescription.set(file("docs/description.html").readText())
	version.set(pluginVersion)
	pluginId.set(packageName)
}

sourceSets {
	main {
		withConvention(KotlinSourceSet::class) {
			listOf(java, kotlin).forEach { it.srcDirs("src", "gen") }
		}
		resources.srcDir("res")
	}

	test {
		withConvention(KotlinSourceSet::class) {
			listOf(java, kotlin).forEach { it.srcDirs("test") }
		}
		resources.srcDir("testData")
	}
}

repositories {
	mavenCentral()
	maven("https://dl.bintray.com/jetbrains/markdown/")
}

dependencies {
	implementation(kotlin("stdlib-jdk8"))
	implementation(group = "org.eclipse.mylyn.github", name = "org.eclipse.egit.github.core", version = "2.1.5") {
		exclude(module = "gson")
	}
	implementation("org.jetbrains", "markdown", "0.2.0")
	testImplementation(kotlin(module = "test-junit"))
	testImplementation(group = "junit", name = "junit", version = "4.12")
}

tasks.register("displayCommitHash") {
	group = "help"
	description = "Display the newest commit hash"
	doFirst { println("Commit hash: $commitHash") }
}

tasks.register("isCI") {
	group = "help"
	description = "Check if it's running in a continuous-integration"
	doFirst { println(if (isCI) "Yes, I'm on a CI." else "No, I'm not on CI.") }
}

// Don't specify type explicitly. Will be incorrectly recognized
val parserRoot = Paths.get("org", "ice1000", "julia", "lang")
val lexerRoot = Paths.get("gen", "org", "ice1000", "julia", "lang")
fun path(more: Iterable<*>) = more.joinToString(File.separator)
fun bnf(name: String) = Paths.get("grammar", "$name-grammar.bnf").toString()
fun flex(name: String) = Paths.get("grammar", "$name-lexer.flex").toString()

val genParser = tasks.register<GenerateParserTask>("genParser") {
	group = "code generation"
	description = "Generate the Parser and PsiElement classes"

	source.set(bnf("julia"))
	targetRoot.set("gen/")
	pathToParser.set(path(parserRoot + "JuliaParser.java"))
	pathToPsiRoot.set(path(parserRoot + "psi"))
	purgeOldFiles.set(true)
}

val genLexer = tasks.register<GenerateLexerTask>("genLexer") {
	group = "code generation"
	description = "Generate the Lexer"

	source.set(flex("julia"))
	targetDir.set(path(lexerRoot))
	targetClass.set("JuliaLexer")
	purgeOldFiles.set(true)

	dependsOn(genParser)
}

val genDocfmtParser = tasks.register<GenerateParserTask>("genDocfmtParser") {
	group = "code generation"
	description = "Generate the Parser for DocumentFormat.jl"

	source.set(bnf("docfmt"))
	targetRoot.set("gen/")

	val root = parserRoot + "docfmt"
	pathToParser.set(path(root + "DocfmtParser.java"))
	pathToPsiRoot.set(path(root + "psi"))
	purgeOldFiles.set(true)
}

val genDocfmtLexer = tasks.register<GenerateLexerTask>("genDocfmtLexer") {
	group = "code generation"
	description = "Generate the Lexer for DocumentFormat.jl"

	source.set(flex("docfmt"))
	targetDir.set(path(lexerRoot + "docfmt"))
	targetClass.set("DocfmtLexer")
	purgeOldFiles.set(true)

	dependsOn(genDocfmtParser)
}

val cleanGenerated = tasks.register("cleanGenerated") {
	group = "clean"
	description = "Remove all generated codes"
	doFirst { delete("gen") }
}

val sortSpelling = tasks.register("sortSpellingFile") {
	val fileName = "spelling.txt"
	val isWindows = "windows" in System.getProperty("os.name").toLowerCase()
	project.exec {
		workingDir = file("$projectDir/res/org/ice1000/julia/lang/editing")
		commandLine = when {
			isWindows -> listOf("sort.exe", fileName, "/O", fileName)
			else -> listOf("sort", fileName, "-f", "-o", fileName)
		}
	}
}

tasks.withType<KotlinCompile>().configureEach {
	dependsOn(
		genParser,
		genLexer,
		genDocfmtParser,
		genDocfmtLexer,
		sortSpelling
	)
	kotlinOptions {
		jvmTarget = VERSION_17.toString()
		languageVersion = "1.7"
		apiVersion = "1.6"
		freeCompilerArgs = listOf("-Xjvm-default=enable")
	}
}

tasks.withType<Delete>().configureEach { dependsOn(cleanGenerated) }
