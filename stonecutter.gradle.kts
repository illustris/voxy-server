plugins {
	id("dev.kikugie.stonecutter")
	// Both Loom variants declared here; build.gradle.kts applies the right one per version.
	id("net.fabricmc.fabric-loom-remap") version "1.15-SNAPSHOT" apply false
	id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT" apply false
}

stonecutter active "26.1.1"

stonecutter parameters {
	// Identifier was called ResourceLocation in Mojang mappings before ~1.21.2.
	// Context-specific replacements avoid colliding with WorldIdentifier (Voxy lib)
	// by matching only when Identifier is preceded by non-letter characters.
	replacements {
		// Identifier was called ResourceLocation before MC 1.21.11.
		string(current.parsed < "1.21.11") {
			// Fully qualified (import)
			replace("resources.Identifier", "resources.ResourceLocation")
			// Type name preceded by whitespace or punctuation
			replace(" Identifier", " ResourceLocation")
			replace("\tIdentifier", "\tResourceLocation")
			replace("(Identifier", "(ResourceLocation")
			replace("<Identifier", "<ResourceLocation")
			replace(",Identifier", ",ResourceLocation")
			// ByteBuf methods
			replace("writeIdentifier", "writeResourceLocation")
			replace("readIdentifier", "readResourceLocation")
			// ResourceKey method
			replace(".identifier()", ".location()")
		}
		// Fabric API renamed PayloadTypeRegistry methods and ClientCommands in 26.x
		string(current.parsed >= "1.20.5" && current.parsed < "26.1") {
			replace("clientboundPlay", "playS2C")
			replace("serverboundPlay", "playC2S")
		}
		string(current.parsed < "26.1") {
			replace("ClientCommands", "ClientCommandManager")
		}
		// Level height methods were renamed between 1.21.1 and 1.21.11
		string(current.parsed < "1.21.11") {
			replace("getMinY()", "getMinBuildHeight()")
			replace("getMaxY()", "getMaxBuildHeight()")
			replace("getMinSectionY()", "getMinSection()")
		}
		// ChunkPos became a record in 26.x; before that x/z are public fields.
		string(current.parsed < "26.1") {
			replace("getPos().x()", "getPos().x")
			replace("getPos().z()", "getPos().z")
			replace("pos.x()", "pos.x")
			replace("pos.z()", "pos.z")
		}
	}

	// Constants for preprocessor conditions
	val mc = current.parsed
	constants["HAS_NEW_NETWORKING"]   = mc >= "1.20.5"    // CustomPacketPayload + StreamCodec + PayloadTypeRegistry
	constants["HAS_IDENTIFIER"]       = mc >= "1.21.11"   // Identifier vs ResourceLocation
	constants["HAS_DEBUG_SCREEN"]     = mc >= "1.21.11"   // DebugScreenEntries F3 API (conservative)
	constants["HAS_RENDER_PIPELINES"] = mc >= "26.1"      // New RenderPipelines / SubmitNodeCollector API
	constants["HAS_PERMISSIONS"]      = mc >= "1.21.11"   // Permissions.COMMANDS_ADMIN
	constants["HAS_FULL_CHUNK_IS_OR_AFTER"] = mc >= "1.20.5" // FullChunkStatus.isOrAfter
	constants["MC_1_20_1"]            = mc < "1.20.5"     // True only for 1.20.1
	constants["DEOBFUSCATED"]         = mc >= "26.1"       // MC ships deobfuscated (no remapping)

	swaps["mod_version"] = "\"${property("mod.version")}\";"
	swaps["minecraft"]   = "\"${node.metadata.version}\";"

	dependencies["fapi"] = node.project.property("deps.fabric_api") as String
}
