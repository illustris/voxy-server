plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom") version "1.15-SNAPSHOT" apply false
}

stonecutter active "26.1.1"

stonecutter parameters {
	// Identifier was called ResourceLocation in Mojang mappings before 1.21.2.
	// Methods on ByteBuf and ResourceKey were similarly renamed.
	// We use swaps instead of replacements so that WorldIdentifier (Voxy lib) is not affected.
	val useIdentifier = current.parsed >= "1.21.2"
	swaps["rl_type"]     = if (useIdentifier) "Identifier"              else "ResourceLocation"
	swaps["rl_import"]   = if (useIdentifier) "net.minecraft.resources.Identifier" else "net.minecraft.resources.ResourceLocation"
	swaps["write_rl"]    = if (useIdentifier) "writeIdentifier"         else "writeResourceLocation"
	swaps["read_rl"]     = if (useIdentifier) "readIdentifier"          else "readResourceLocation"
	swaps["rl_parse"]    = if (useIdentifier) "Identifier.parse"        else "ResourceLocation.parse"
	swaps["rl_method"]   = if (useIdentifier) "identifier"              else "location"

	// Constants for preprocessor conditions
	val mc = current.parsed
	constants["HAS_NEW_NETWORKING"]   = mc >= "1.20.5"    // CustomPacketPayload + StreamCodec + PayloadTypeRegistry
	constants["HAS_IDENTIFIER"]       = mc >= "1.21.2"    // Identifier vs ResourceLocation
	constants["HAS_DEBUG_SCREEN"]     = mc >= "1.21.11"   // DebugScreenEntries F3 API (conservative)
	constants["HAS_RENDER_PIPELINES"] = mc >= "26.1"      // New RenderPipelines / SubmitNodeCollector API
	constants["HAS_PERMISSIONS"]      = mc >= "26.1"      // Permissions.COMMANDS_ADMIN
	constants["HAS_FULL_CHUNK_IS_OR_AFTER"] = mc >= "1.20.5" // FullChunkStatus.isOrAfter
	constants["MC_1_20_1"]            = mc < "1.20.5"     // True only for 1.20.1

	swaps["mod_version"] = "\"${property("mod.version")}\";"
	swaps["minecraft"]   = "\"${node.metadata.version}\";"

	dependencies["fapi"] = node.project.property("deps.fabric_api") as String
}
