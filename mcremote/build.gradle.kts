plugins {
    `java-library`
    `maven-publish`
    idea
    id("com.gradleup.shadow") version "8.3.6"
}

// Set Minecraft version and plugin version here.
val mcVersion: String = "1.21.4"
val pluginVersion: String = "0.6.22"

// Set directory for the PaperMC server.
val homeDir: String = System.getenv("HOME") ?: System.getProperty("user.home")
val mcDir = file("$homeDir/MINECRAFT_SERVERS/PaperMC")

group = "club.code2create"
version = "$mcVersion-$pluginVersion"
val projectDir = project.rootDir
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$mcVersion-R0.1-SNAPSHOT")
    compileOnly("net.luckperms:api:5.4")
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


fun loadFtpSettings(): Map<String, String> {
    val ftpFile = file("ftp_settings.mk")
    if (!ftpFile.exists()) {
        println("ftp_settings.mk が見つかりません。FTP のデプロイはスキップされます。")
        return emptyMap()
    }
    return ftpFile.readLines()
        .filter { it.isNotBlank() && !it.trim().startsWith("#") }
        .map { it.trim() }
        .mapNotNull { line ->
            // FTP_USER := someuser  または  FTP_USER = someuser
            val parts = line.split(Regex("\\s*[:=]+\\s*"), limit = 2)
            if (parts.size == 2) {
                parts[0] to parts[1]
            } else {
                null
            }
        }
        .toMap()
}

// read ftp_settings.mk
val ftpSettings = loadFtpSettings()
val ftpUser = ftpSettings["FTP_USER"] ?: ""
val ftpPass = ftpSettings["FTP_PASS"] ?: ""
val ftpHost = ftpSettings["FTP_HOST"] ?: ""
val ftpPath = ftpSettings["FTP_PATH"] ?: ""

// ──────────────────────────────
// タスク定義
// ──────────────────────────────

/**
 * reloadPlugin: プラグインの再ビルド後、生成されたjarをサーバーのpluginsフォルダーにコピーする
 */
tasks.register("reloadPlugin") {
    group = "deployment"
    description = "Build the plugin and copy the jar to the server's plugins folder."
    dependsOn("build")
    doLast {
        val pluginJar = layout.buildDirectory.file("libs/mc-remote-$mcVersion-$pluginVersion.jar").get().asFile
        val pluginsDir = file("$mcDir/plugins")
        println("Reloading plugin...")
        // 古いプラグイン（名前にMcRemoteまたはmc-remoteを含む）を削除
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
    }
}

/**
 * runServer: サーバーをscreenでバックグラウンド起動する
 */
tasks.register<Exec>("runServer") {
    group = "server"
    description = "Launch the Minecraft server in a detached screen session."
    workingDir = mcDir
    commandLine("screen", "-dmS", "minecraft", "java", "-Xmx8G", "-Xms8G", "-jar", "paper.jar", "nogui")
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
tasks.register<Exec>("deploy") {
    group = "deployment"
    description = "Deploy the plugin jar to the FTP server."
    dependsOn("build")
    doFirst {
        println("Deploying mc-remote-$mcVersion-$pluginVersion.jar via FTP to: $ftpHost$ftpPath")
    }
    val jarPath = layout.buildDirectory.get().asFile.resolve("libs/mc-remote-$mcVersion-$pluginVersion.jar").absolutePath    // lftpコマンドをシェル経由で実行する形
    val ftpCommand = "lftp ftp://$ftpUser:$ftpPass@$ftpHost$ftpPath -e \"glob -a rm mc-remote*.jar; put $jarPath; bye\""
    commandLine("sh", "-c", ftpCommand)
}

/**
 * trigger: GitHub Actions をトリガーするため、git tag を作成し push する
 */
// Git タグを作成するタスク
tasks.register<Exec>("tagRelease") {
    group = "release"
    description = "Create a git tag for the new release."
    // バージョン情報 (mcVersion, pluginVersion) は上位で定義されている前提です。
    commandLine("git", "tag", "v$mcVersion-$pluginVersion")
}

// Git タグをリモート（origin）にプッシュするタスク
tasks.register<Exec>("pushRelease") {
    group = "release"
    description = "Push the git tag to origin."
    commandLine("git", "push", "origin", "v$mcVersion-$pluginVersion")
}

// これらのタスクをまとめ、GitHub Actions などのトリガーに利用するためのタスク
tasks.register("trigger") {
    group = "release"
    description = "Trigger GitHub Actions by tagging a new release."
    // 依存タスクにすることで、先に tagRelease と pushRelease が実行されるようにする
    dependsOn("tagRelease", "pushRelease")
    doLast {
        println("Git tag v$mcVersion-$pluginVersion has been created and pushed to origin.")
    }
}
