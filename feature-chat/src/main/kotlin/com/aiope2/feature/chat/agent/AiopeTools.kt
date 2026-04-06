package com.aiope2.feature.chat.agent

import android.content.Context
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import com.aiope2.core.terminal.shell.ShellExecutor

@LLMDescription("Tools for executing commands and managing files on Android")
class AiopeTools(private val ctx: Context) : ToolSet {

  @Tool
  @LLMDescription("Execute a shell command on Android and return stdout+stderr. Use for running programs, listing files, checking system info, etc.")
  fun run_sh(
    @LLMDescription("The shell command to execute, e.g. 'ls -la /sdcard' or 'cat /etc/hosts'")
    command: String
  ): String {
    return try {
      val result = ShellExecutor.exec(command)
      if (result.length > 4000) result.take(4000) + "\n...(truncated)" else result
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  @Tool
  @LLMDescription("Read the contents of a file at the given path")
  fun read_file(
    @LLMDescription("Absolute path to the file to read")
    path: String
  ): String {
    return try {
      val f = java.io.File(path)
      if (!f.exists()) return "Error: file not found: $path"
      if (f.length() > 50000) return "Error: file too large (${f.length()} bytes)"
      f.readText()
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  @Tool
  @LLMDescription("Write content to a file at the given path, creating parent directories if needed")
  fun write_file(
    @LLMDescription("Absolute path to the file to write")
    path: String,
    @LLMDescription("The content to write to the file")
    content: String
  ): String {
    return try {
      val f = java.io.File(path)
      f.parentFile?.mkdirs()
      f.writeText(content)
      "Written ${content.length} bytes to $path"
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }

  @Tool
  @LLMDescription("List files and directories at the given path")
  fun list_directory(
    @LLMDescription("Absolute path to the directory to list")
    path: String
  ): String {
    return try {
      val f = java.io.File(path)
      if (!f.isDirectory) return "Error: not a directory: $path"
      f.listFiles()?.joinToString("\n") { entry ->
        val type = if (entry.isDirectory) "d" else "-"
        val size = if (entry.isFile) entry.length().toString() else ""
        "$type ${entry.name} $size"
      } ?: "Empty directory"
    } catch (e: Exception) {
      "Error: ${e.message}"
    }
  }
}
