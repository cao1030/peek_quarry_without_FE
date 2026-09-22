pluginManagement {
    repositories {
        maven {
            // RetroFuturaGradle / GTNH 生态插件
            name = "GTNH Maven"
            url = uri("https://nexus.gtnewhorizons.com/repository/public/")
            mavenContent {
                includeGroupByRegex("com\\.gtnewhorizons\\..+")
                includeGroup("com.gtnewhorizons")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        mavenLocal()
    }
}

// 说明：这里刻意不引入 foojay 工具链自动下载插件，少一个网络依赖。
// 如果你的机器上没有 JDK 8，可以加回来：
//   plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

// 产物名跟着这个走：peek_quarry-1.0.0.jar
// （仓库目录叫 peek_quarry_without_FE，但 modid 仍然是 peek_quarry）
rootProject.name = "peek_quarry"
