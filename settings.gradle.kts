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
		versions("1.20.1", "1.21.1", "1.21.11", "26.1.1")
		vcsVersion = "26.1.1"
	}
}

rootProject.name = "voxy-server"
