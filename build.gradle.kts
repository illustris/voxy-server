plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom-remap")
	`maven-publish`
}

val mcVersion = stonecutter.current.version
val javaTarget = property("mod.java_version").toString().toInt()
val requiredJava = JavaVersion.toVersion(javaTarget)

version = property("mod.version").toString()
group = property("mod.group").toString()
base.archivesName = "voxy-server-${mcVersion}"

loom {
	splitEnvironmentSourceSets()
	mods {
		register("voxy-server") {
			sourceSet(sourceSets["main"])
			sourceSet(sourceSets["client"])
		}
	}
}

repositories {
	mavenCentral()
}

// Pick the right Voxy jar: libs/voxy-<mc>.jar, falling back to libs/voxy.jar
val voxyJar = file("libs/voxy-${mcVersion}.jar").let {
	if (it.exists()) it else file("libs/voxy.jar")
}

dependencies {
	minecraft("com.mojang:minecraft:${property("deps.minecraft")}")

	mappings(loom.officialMojangMappings())

	implementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
	implementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

	implementation(files(voxyJar))

	// LWJGL core for dedicated servers (MC only bundles it for clients).
	// Must match the version Voxy was compiled against.
	val lwjgl = property("mod.lwjgl_version").toString()
	include(implementation("org.lwjgl:lwjgl:${lwjgl}")!!)
	include("org.lwjgl:lwjgl:${lwjgl}:natives-linux")
	include("org.lwjgl:lwjgl:${lwjgl}:natives-windows")

	// xxHash for Merkle tree hashing
	include(implementation("net.openhft:zero-allocation-hashing:0.16")!!)

	// RocksDB for metadata stores (timestamps, section hashes)
	implementation("org.rocksdb:rocksdbjni:10.2.1")
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

	processResources {
		val hasDebugScreen = stonecutter.current.parsed >= "1.21.11"
		val props = mapOf(
			"version"           to project.version,
			"minecraft_version" to property("deps.minecraft"),
			"loader_version"    to property("deps.fabric_loader"),
			"mc_dep"            to property("mod.mc_dep"),
			"java_version"      to javaTarget,
		)
		inputs.properties(props)
		filteringCharset = "UTF-8"

		// Build the mixin list -- include client mixin config only when supported
		val mixinList = buildList {
			add("\"voxy-server.mixins.json\"")
			if (hasDebugScreen) add("\"voxy-server-client.mixins.json\"")
		}
		val allProps = props + mapOf("mixin_list" to mixinList.joinToString(",\n\t\t"))

		filesMatching("fabric.mod.json") { expand(allProps) }
		filesMatching("voxy-server.mixins.json") { expand(props) }
		filesMatching("voxy-server-client.mixins.json") { expand(props) }

		// Exclude the client mixin config on versions that lack DebugScreenEntries
		if (!hasDebugScreen) {
			exclude("voxy-server-client.mixins.json")
		}
	}

	jar {
		from("LICENSE") {
			rename { "${it}_voxy-server" }
		}
	}

	register<Copy>("buildAndCollect") {
		group = "build"
		from(remapJar.map { it.archiveFile })
		into(rootProject.layout.buildDirectory.dir("libs/${mcVersion}"))
		dependsOn("build")
	}
}
