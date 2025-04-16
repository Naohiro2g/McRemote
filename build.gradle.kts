import java.util.*

plugins {
    `java-library`
    `maven-publish`
    idea
}

// ──────── plugin version ──────────────────────────────────────────────── //
// The plugin jar will be like "mc-remote-1.21.4-1.0.9.jar".
val mcVersion: String = "1.21.5"
val pluginVersion: String = "1.1.0rc10"
// ──────── Local Minecraft Server for development ──────────────────────── //
val homeDir: String = System.getenv("HOME") ?: System.getProperty("user.home")
val mcDir = file("$homeDir/MINECRAFT_SERVERS/PaperMC")  // Minecraft server directory
val serverJar = "paper.jar"  // Server jar file name
val memoryS = "8G"  // Initial memory size
val memoryX = "8G"  // Maximum memory size
// ──────────────────────────────────────────────────────────────────────── //

group = "club.code2create"
version = "$mcVersion-$pluginVersion"
val projectDir = project.rootDir
java.sourceCompatibility = JavaVersion.VERSION_21

val ftpSettingsFile = file("$projectDir/ftp_settings.mk")
// FTP settings file 'ftp_settings.mk' should be in the same directory as this build.gradle.kts file.
// The content of the file should be like:
//# ftp_settings.mk:
//ftp1.FTP_USER := ftpuser
//ftp1.FTP_PASS := jdijidjidjidji
//ftp1.FTP_HOST := c2cc.xgames.jp:10021
//ftp1.FTP_PATH := /minecraft/plugins/
// you can add more environments like ftp2, ftp3, etc.

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")  // Paper API
    compileOnly("net.luckperms:api:5.4")  // LuckPerms API
}

var runServerCmd: String
var stopServerCmd: String
val isWindows: Boolean = System.getProperty("os.name").lowercase(Locale.getDefault()).contains("windows")
if (isWindows) {
    runServerCmd = "cd ${mcDir.absolutePath} && java -Xmx$memoryX -Xms$memoryS -jar $serverJar"
    stopServerCmd = "echo \"Stop command for Windows not implemented. Please stop the server manually.\""
} else {
    runServerCmd = "cd ${mcDir.absolutePath} && screen -dmS minecraft java -Xmx$memoryX -Xms$memoryS -jar $serverJar && echo \"Minecraft server started successfully.\""
    stopServerCmd = "cd ${mcDir.absolutePath} && if screen -list | grep -q minecraft; then screen -S minecraft -X stuff 'stop\r' && sleep 5; else echo \"No screen session found for 'minecraft'\"; fi"
}


tasks.jar {
    archiveBaseName.set("mc-remote")
    archiveVersion.set(version.toString())
    archiveClassifier.set("")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("project" to mapOf("version" to version.toString())))
    }
    inputs.property("version", version.toString())
}


/**
 * reloadPlugin: プラグインの再ビルド後、生成されたjarをサーバーのpluginsフォルダーにコピーする
 */
tasks.register("reloadPlugin") {
    group = "deployment"
    description = "Build the plugin and copy the jar to the server's plugins folder."
    dependsOn("build")
    doLast {
        val pluginJar = layout.buildDirectory.file("libs/mc-remote-$version.jar").get().asFile
        val pluginsDir = file("$mcDir/plugins")
        println("Reloading plugin...")
        // 古いプラグインとディレクトリ（名前にmc-remoteまたはMcRemoteを含む）を削除
        pluginsDir.listFiles { file ->
            file.name.matches(Regex("(?i)(McRemote.*)|mc-remote.*\\.jar"))
        }?.forEach {
            it.deleteRecursively()
        }
        copy {
            from(pluginJar)
            into(pluginsDir)
        }
        println("Plugin reloaded successfully.")
    }
}

/**
 * stopServer: サーバーをscreen経由で停止する
 */
tasks.register("stopServer") {
    group = "server"
    description = "Stop the Minecraft server if it is running."

    doLast {
        if (!isWindows) {
            println("Checking if the Minecraft server is running...")
            // コマンドを実行し、その結果を取得
            val process = ProcessBuilder("screen", "-list")
                .redirectErrorStream(true)
                .start()
            // 実行結果を読み取る
            val result = process.inputStream.bufferedReader().readText()
            // サーバーが動いているかを確認
            if (result.contains("minecraft")) {
                println("Minecraft server is running. Sending stop command...")
                ProcessBuilder("screen", "-S", "minecraft", "-p", "0", "-X", "stuff", "stop\r")
                    .redirectErrorStream(true)
                    .start()
                println("Stop command sent. Waiting for the server to stop...")
                Thread.sleep(5000) // サーバー停止を待つ
            } else {
                println("No screen session found for 'minecraft'. Server might already be stopped.")
            }
        } else {
            println("ScreenコマンドはWindowsではサポートされていません。手動でサーバーを停止してください。")
        }
    }
}

/**
 * runServer: サーバーをscreenでバックグラウンド起動する
 */
tasks.register<Exec>("runServer") {
    group = "server"
    description = "Launch the Minecraft server session."
    workingDir = mcDir
    doFirst {
        if (!isWindows) {
            commandLine("screen", "-dmS", "minecraft", "java", "-Xmx$memoryX", "-Xms$memoryS", "-jar", serverJar, "nogui")
        } else {
            println("ScreenコマンドはWindowsではサポートされていません。代わりにサーバーを直接起動します。")
            commandLine("java", "-Xmx$memoryX", "-Xms$memoryS", "-jar", serverJar, "nogui")
        }
    }
    doLast {
        println("Minecraft server started successfully.")
    }
}

/**
 * restartServer: サーバーの停止と起動を連続で行う
 */
tasks.register("restartServer") {
    group = "server"
    description = "Restart the Minecraft server (stop, then start)."
    dependsOn("stopServer")
    finalizedBy("runServer")
}

/**
 * live: プラグインのリロードとサーバーの再起動を連携して行う
 */
tasks.register("live") {
    group = "deployment"
    description = "Reload the plugin and restart the server."
    dependsOn("reloadPlugin", "restartServer")
}

/**
 * deploy: FTP経由でプラグインjarを指定サーバーにデプロイする（lftpを利用）
 * ftp_settings.mk で定義された FTP_USER, FTP_PASS, FTP_HOST, FTP_PATH を使用します。
 */
fun loadFtpSettings(env: String): Map<String, String> {
    val ftpFile = file("ftp_settings.mk")
    if (!ftpFile.exists()) {
        println("FTP接続設定ファイルが見つかりません。FTP のデプロイはスキップされます。")
        return emptyMap()
    }

    return ftpFile.readLines()
        .filter { it.contains(":=") && it.startsWith("$env.") }
        .associate { line ->
            val (key, value) = line.split(":=", limit = 2)
            key.removePrefix("$env.").trim() to value.trim()
        }
}

// 環境ごとにFTPタスクを作成
listOf("ftp1", "ftp2").forEach { env ->
    tasks.register<Exec>("deploy_$env") {
        group = "deployment"
        description = "Deploy the plugin jar to the $env FTP server."
        dependsOn("build")

        doFirst {
            val ftpSettings = loadFtpSettings(env)
            val ftpUser = ftpSettings["FTP_USER"] ?: ""
            val ftpPass = ftpSettings["FTP_PASS"] ?: ""
            val ftpHost = ftpSettings["FTP_HOST"] ?: ""
            val ftpPath = ftpSettings["FTP_PATH"] ?: ""

            if (ftpUser.isBlank() || ftpPass.isBlank() || ftpHost.isBlank() || ftpPath.isBlank()) {
                println("FTP設定が不完全なため、$env へのデプロイをスキップします。")
                enabled = false // タスクをスキップ
                return@doFirst
            }

            println("Deploying mc-remote-$version.jar via FTP to: $ftpHost$ftpPath")

            val jarPath = layout.buildDirectory.get().asFile.resolve("libs/mc-remote-$version.jar").absolutePath
            val ftpCommand = """
                lftp ftp://$ftpUser:$ftpPass@$ftpHost$ftpPath -e "
                rm -fr McRemote;
                glob -a rm mc-remote*.jar;
                put $jarPath;
                bye"
            """.trimIndent()
            commandLine("sh", "-c", ftpCommand)
        }
    }
}

/**
 * trigger: GitHub Actions をトリガーするため、git tag を作成し push する
 */
// Git タグを作成するタスク
tasks.register<Exec>("tagRelease") {
    group = "release"
    description = "Create a git tag for the new release."
    commandLine("git", "tag", "v$version")
}

// Git タグをリモート（origin）にプッシュするタスク
tasks.register<Exec>("pushRelease") {
    group = "release"
    description = "Push the git tag to origin."
    commandLine("git", "push", "origin", "v$version")
}

// これらのタスクをまとめ、GitHub Actions などのトリガーに利用するためのタスク
tasks.register("trigger") {
    group = "release"
    description = "Trigger GitHub Actions by tagging a new release."
    dependsOn("tagRelease", "pushRelease")
    doLast {
        println("Git tag v$version has been created and pushed to origin.")
    }
}

tasks.register("printMcVersion") {
    doLast {
        println(mcVersion)
    }
}

tasks.register("printPluginVersion") {
    doLast {
        println(pluginVersion)
    }
}
