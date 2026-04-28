import java.util.zip.ZipFile

plugins {
	id("dev.kikugie.stonecutter")
}

// MC 26.1+ ships deobfuscated: use plain fabric-loom (no remapping).
// Older MC: use fabric-loom-remap (handles intermediary remapping).
val deobfuscated = stonecutter.current.parsed >= "26.1"
if (deobfuscated) {
	apply(plugin = "net.fabricmc.fabric-loom")
} else {
	apply(plugin = "net.fabricmc.fabric-loom-remap")
}

val mcVersion = stonecutter.current.version
val javaTarget = property("mod.java_version").toString().toInt()
val requiredJava = JavaVersion.toVersion(javaTarget)

version = property("mod.version").toString()
group = property("mod.group").toString()
base.archivesName = "voxy-server-${mcVersion}"

// Access the Loom extension generically since the plugin was applied dynamically
val loom = extensions.getByName("loom") as net.fabricmc.loom.api.LoomGradleExtensionAPI

loom.splitEnvironmentSourceSets()
loom.mods.register("voxy-server") {
	sourceSet(sourceSets["main"])
	sourceSet(sourceSets["client"])
}

repositories {
	maven("https://maven.fabricmc.net/") { name = "Fabric" }
	mavenCentral()
}

// Pick the right Voxy jar: libs/voxy-<mc>.jar, falling back to libs/voxy.jar
val voxyJar = rootProject.file("libs/voxy-${mcVersion}.jar").let {
	if (it.exists()) it else rootProject.file("libs/voxy.jar")
}

// Detect whether the voxy jar is intermediary-mapped (Modrinth distribution
// format) or already in production names. Intermediary jars must go through
// Loom remapping; production-name jars cannot. The access widener header is
// the most reliable signal.
val voxyIsIntermediary: Boolean = ZipFile(voxyJar).use { zip ->
	val awEntry = zip.entries().toList().firstOrNull { it.name.endsWith(".accesswidener") }
	if (awEntry == null) false else {
		val header = zip.getInputStream(awEntry).bufferedReader().use { it.readLine() ?: "" }
		header.split("\t").getOrNull(2) == "intermediary"
	}
}

dependencies {
	if (deobfuscated) {
		// Deobfuscated MC: no mappings, no mod-specific configurations
		add("minecraft", "com.mojang:minecraft:${property("deps.minecraft")}")
		add("implementation", "net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
		add("implementation", "net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
	} else {
		// Obfuscated MC: use Loom configurations with mappings
		add("minecraft", "com.mojang:minecraft:${property("deps.minecraft")}")
		add("mappings", loom.officialMojangMappings())
		add("modImplementation", "net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
		add("modImplementation", "net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")
	}

	if (voxyIsIntermediary) {
		add("modImplementation", files(voxyJar))
	} else {
		add("implementation", files(voxyJar))
	}

	// LWJGL core for dedicated servers (MC only bundles it for clients).
	val lwjgl = property("mod.lwjgl_version").toString()
	add("include", add("implementation", "org.lwjgl:lwjgl:${lwjgl}")!!)
	add("include", "org.lwjgl:lwjgl:${lwjgl}:natives-linux")
	add("include", "org.lwjgl:lwjgl:${lwjgl}:natives-windows")

	// xxHash for Merkle tree hashing
	add("include", add("implementation", "net.openhft:zero-allocation-hashing:0.16")!!)

	// RocksDB for metadata stores (timestamps, section hashes)
	add("implementation", "org.rocksdb:rocksdbjni:10.2.1")
}

java {
	withSourcesJar()
	targetCompatibility = requiredJava
	sourceCompatibility = requiredJava
}

tasks {
	withType<JavaCompile> {
		options.encoding = "UTF-8"
		options.release.set(javaTarget)
		options.isDeprecation = true
	}

	named<ProcessResources>("processResources") {
		val hasDebugScreen = stonecutter.current.parsed >= "1.21.11"
		val props = mapOf(
			"version"           to project.version,
			"minecraft_version" to project.property("deps.minecraft"),
			"loader_version"    to project.property("deps.fabric_loader"),
			"mc_dep"            to project.property("mod.mc_dep"),
			"java_version"      to javaTarget,
		)
		inputs.properties(props)
		filteringCharset = "UTF-8"

		filesMatching("fabric.mod.json") { expand(props) }
		filesMatching("voxy-server.mixins.json") { expand(props) }
		filesMatching("voxy-server-client.mixins.json") { expand(props) }

		// For versions without DebugScreenEntries, overwrite the client mixin
		// config with an empty one (no client mixins, but the file must exist
		// since fabric.mod.json references it).
		if (!hasDebugScreen) {
			doLast {
				val f = destinationDir.resolve("voxy-server-client.mixins.json")
				f.writeText("""{"required":true,"package":"me.cortex.voxy.server.mixin","compatibilityLevel":"JAVA_${javaTarget}","injectors":{"defaultRequire":1}}""")
			}
		}
	}

	named<Jar>("jar") {
		from("LICENSE") {
			rename { "${it}_voxy-server" }
		}
	}

	// For deobfuscated MC the output is "jar"; for obfuscated MC it's "remapJar"
	val outputTask = if (deobfuscated) "jar" else "remapJar"

	register<Copy>("buildAndCollect") {
		group = "build"
		from(named(outputTask).map { (it as AbstractArchiveTask).archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/${mcVersion}"))
		dependsOn("build")
	}
}
