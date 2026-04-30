pluginManagement {
	repositories {
		maven("https://maven.fabricmc.net/") { name = "Fabric" }
		maven("https://maven.kikugie.dev/snapshots") { name = "Stonecutter" }
		gradlePluginPortal()
	}
}

plugins {
	id("dev.kikugie.stonecutter") version "0.9"
}

stonecutter {
	create(rootProject) {
		// 1.20.1 dropped: voxy upstream's 1.20.1 backport is not maintained
		// and the surface-area difference (HudRenderCallback signatures,
		// Options.renderDebug, ChunkStatus packaging, CycleButton API)
		// costs more than the support is worth. Re-add only if a current
		// voxy 1.20.1 jar matching the commonImpl API ships.
		versions("1.21.1", "1.21.11", "26.1.2")
		vcsVersion = "26.1.2"
	}
}

rootProject.name = "voxy-server"
