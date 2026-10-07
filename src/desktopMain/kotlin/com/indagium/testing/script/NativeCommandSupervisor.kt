package com.indagium.testing.script

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission

/** Extracts the small OS-native process-tree supervisor bundled with each desktop distribution. */
internal object NativeCommandSupervisor {
    private val executable: File by lazy(LazyThreadSafetyMode.SYNCHRONIZED, ::extract)

    fun command(spec: HostCommandSpec, statusFile: File): List<String> =
        buildList(spec.command.size + 4) {
            add(executable.absolutePath)
            add("--status")
            add(statusFile.absolutePath)
            add("--")
            addAll(spec.command)
        }

    @Suppress("ThrowsCount") // Each initialization stage fails closed with a different actionable message.
    private fun extract(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val (platform, filename) = when {
            os.contains("mac") || os.contains("darwin") -> "macos" to "indagium_command_supervisor"
            os.contains("linux") -> "linux" to "indagium_command_supervisor"
            os.contains("win") -> "windows" to "indagium_command_supervisor.exe"
            else -> throw IOException("No native command supervisor is available for ${System.getProperty("os.name")}.")
        }
        val resource = "/native/$platform/$filename"
        val directory = try {
            Files.createTempDirectory("indagium-command-supervisor-").toFile()
        } catch (failure: IOException) {
            throw IOException("Could not create a private directory for the command supervisor.", failure)
        }
        val executable = File(directory, filename)
        try {
            val input = NativeCommandSupervisor::class.java.getResourceAsStream(resource)
                ?: throw IOException("The bundled command supervisor is missing ($resource); refusing to run an unsupervised command.")
            input.use { Files.copy(it, executable.toPath()) }
            if (platform != "windows") {
                try {
                    Files.setPosixFilePermissions(executable.toPath(), setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    ))
                } catch (failure: UnsupportedOperationException) {
                    if (!executable.setExecutable(true, true)) {
                        throw IOException("Could not make the bundled command supervisor executable.", failure)
                    }
                }
            }
            if (!executable.canExecute() && platform != "windows") {
                throw IOException("Could not make the bundled command supervisor executable.")
            }
            directory.deleteOnExit()
            executable.deleteOnExit()
            return executable
        } catch (failure: IOException) {
            directory.deleteRecursively()
            throw IOException("Could not initialize the bundled command supervisor.", failure)
        } catch (failure: SecurityException) {
            directory.deleteRecursively()
            throw IOException("Could not initialize the bundled command supervisor.", failure)
        }
    }
}
