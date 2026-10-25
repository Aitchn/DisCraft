Official MDK template source (unmodified build.gradle retained alongside this file):
https://github.com/MinecraftForge/MinecraftForge/tree/d13dd327bdc75c010f3358992e80e7e92f51e030/mdk

Wrapper scripts/JAR/properties originate from the same official 1.20.1 branch commit.
Gradle 8.8 is the wrapper selected by that branch, rather than an environment smoke version.
The distribution checksum comes from https://services.gradle.org/distributions/gradle-8.8-bin.zip.sha256 .
This is an adaptation of official MDK source, not a downloaded/verified Forge Maven MDK ZIP.
Forge 47.4.0 is the initial pinned Minecraft 1.20.1 target. ForgeGradle uses the MDK's 6.x range.
No compiled Forge artifact or Minecraft runtime is vendored here.
