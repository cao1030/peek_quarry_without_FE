plugins {
    id("java-library")
    id("maven-publish")
    id("eclipse")
    id("com.gtnewhorizons.retrofuturagradle") version "2.0.4"
}

// ---------------------------------------------------------------------------
// 从 gradle.properties 读取元信息
// ---------------------------------------------------------------------------
val modName: String by project
val modId: String by project
val modGroup: String by project
val modVersion: String by project
val modAuthors: String by project
val modDescription: String by project
val minecraftVersion: String by project
// 注意：变量名不要和 minecraft 扩展里的属性同名（mcpMappingChannel / mcpMappingVersion），
// 否则在 minecraft { } 块内会解析成扩展自身的属性，触发 "Circular evaluation detected"。
// 这里用 gradleProperty 显式取值，属性名仍保留可读的 mcpMappingChannel / mcpMappingVersion。
val mcpChannel: String = providers.gradleProperty("mcpMappingChannel").get()
val mcpVersion: String = providers.gradleProperty("mcpMappingVersion").get()
val developmentEnvironmentUserName: String by project

group = modGroup
version = modVersion

// ---------------------------------------------------------------------------
// Java 工具链：用 JDK 8 编译产出 1.7.10 能加载的字节码
// ---------------------------------------------------------------------------
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// ---------------------------------------------------------------------------
// RFG (RetroFuturaGradle) 配置
// ---------------------------------------------------------------------------
minecraft {
    mcVersion.set(minecraftVersion)
    username.set(developmentEnvironmentUserName)

    // MCP 映射，用于反混淆依赖（deobf）与开发环境
    mcpMappingChannel.set(mcpChannel)
    mcpMappingVersion.set(mcpVersion)

    // 生成 com.peek.quarry.Tags 类，把版本号注入源码
    injectedTags.put("VERSION", project.version)
    injectedTags.put("MODID", modId)
    injectedTags.put("NAME", modName)

    // 运行客户端/服务端时，对本 mod 包开启断言
    extraRunJvmArguments.add("-ea:${project.group}")

    // 需要 Mixin 时打开下面这行（并参考 README 添加 mixin 配置文件）
    // extraTweakClasses.add("org.spongepowered.asm.launch.MixinTweaker")
}

tasks.injectTags.configure {
    outputClassName.set("${project.group}.Tags")
}

// ---------------------------------------------------------------------------
// 把版本号写进 mcmod.info
// ---------------------------------------------------------------------------
tasks.processResources.configure {
    val projVersion = project.version.toString()
    val projAuthors = modAuthors
    val projDescription = modDescription
    val projName = modName
    val projId = modId
    val projMcVersion = minecraftVersion

    inputs.property("version", projVersion)
    inputs.property("authors", projAuthors)
    inputs.property("description", projDescription)

    filesMatching("mcmod.info") {
        expand(
            mapOf(
                "modVersion" to projVersion,
                "modAuthors" to projAuthors,
                "modDescription" to projDescription,
                "modName" to projName,
                "modId" to projId,
                "mcVersion" to projMcVersion
            )
        )
    }
}

// ---------------------------------------------------------------------------
// 仓库
// ---------------------------------------------------------------------------
repositories {
    maven {
        name = "GTNH Maven"
        url = uri("https://nexus.gtnewhorizons.com/repository/public/")
    }
    maven {
        name = "OvermindDL1 Maven"
        url = uri("https://gregtech.overminddl1.com/")
    }
    mavenCentral()
}

// ---------------------------------------------------------------------------
// 依赖
//
//  - compileOnly / api / implementation : 编译期与运行期
//  - runtimeOnlyNonPublishable          : 只在 runClient/runServer 里出现，不参与发布
//  - rfg.deobf(...)                     : 把发布版 jar 反混淆后再参与编译
//
//  几个可直接抄的例子：
//    runtimeOnlyNonPublishable("com.github.GTNewHorizons:NotEnoughItems:2.8.118-GTNH:dev")
//    api(rfg.deobf("curse.maven:ic2-242638:2353971"))
//    api(rfg.deobf(project.files("libs/SomeMod-1.0.jar")))
// ---------------------------------------------------------------------------
val runtimeOnlyNonPublishable: Configuration by configurations.creating {
    description = "Runtime only dependencies that are not published alongside the jar"
    isCanBeConsumed = false
    isCanBeResolved = false
}
listOf(configurations.runtimeClasspath, configurations.testRuntimeClasspath).forEach {
    it.configure { extendsFrom(runtimeOnlyNonPublishable) }
}

dependencies {
    // 把需要一起测试的 mod 放到 libs/ 下，然后取消注释：
    // runtimeOnlyNonPublishable(rfg.deobf(project.files("libs/HBM-NTM-[1.0.27_X5778_H261].jar")))
}

// ---------------------------------------------------------------------------
// 发布（按需保留；不发布可以整段删掉）
// ---------------------------------------------------------------------------
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}

// ---------------------------------------------------------------------------
// IDE
// ---------------------------------------------------------------------------
eclipse {
    classpath {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

// IntelliJ 自动下载源码/文档需要额外的 idea-ext 插件，默认不启用（少一个网络依赖）。
// 需要的话自行加回：
//   plugins { id("org.jetbrains.gradle.plugin.idea-ext") version "1.1.8" }
//   idea { module { isDownloadJavadoc = true; isDownloadSources = true; inheritOutputDirs = true } }
