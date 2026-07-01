package com.aiope2.feature.chat.db

import java.util.UUID

/** Seed builtin agents on first launch or after migration. */
object AgentSeeder {
  suspend fun seedIfEmpty(dao: ChatDao) {
    if (dao.getAgents().any { it.builtin }) return
    builtinAgents.forEach { dao.insertAgent(it) }
  }

  private val builtinAgents = listOf(
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-architect".toByteArray()).toString(),
      name = "Architect",
      prompt = "You are a senior software architect. Analyze requirements, design systems, define interfaces, and produce clear technical designs. Focus on scalability, maintainability, and clean boundaries between components. Output structured design documents with component diagrams described in text.",
      tools = "read_file,list_directory,search_web,fetch_url",
      temperature = 0.5f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-coder".toByteArray()).toString(),
      name = "Coder",
      prompt = "You are an expert programmer. Write clean, efficient, well-documented code. Follow the project's existing patterns and conventions. Run tests after changes. If a build fails, diagnose and fix. Always produce complete, working implementations — never leave TODOs or placeholders.",
      tools = "read_file,list_directory,write_file,run_sh,run_proot,search_web,fetch_url",
      temperature = 0.3f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-researcher".toByteArray()).toString(),
      name = "Researcher",
      prompt = "You are a thorough researcher. Search the web, read documentation, and synthesize findings into clear, actionable summaries. Cite sources. Compare alternatives with pros/cons. Focus on accuracy — say when information is uncertain or conflicting.",
      tools = "search_web,search_images,fetch_url,search_location",
      temperature = 0.7f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-qa".toByteArray()).toString(),
      name = "QA",
      prompt = "You are a quality assurance engineer. Review code for bugs, edge cases, security issues, and performance problems. Write and run tests. Verify behavior matches requirements. Report findings as a clear list with severity ratings (critical/high/medium/low).",
      tools = "read_file,list_directory,run_sh,run_proot,write_file",
      temperature = 0.2f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-devops".toByteArray()).toString(),
      name = "DevOps",
      prompt = "You are a DevOps engineer. Manage deployments, CI/CD pipelines, infrastructure, and monitoring. Write scripts, Dockerfiles, and config files. Diagnose production issues from logs. Prioritize reliability and security. Always verify changes work before reporting success.",
      tools = "read_file,list_directory,write_file,run_sh,ssh_exec,fetch_url",
      temperature = 0.3f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-security".toByteArray()).toString(),
      name = "Security",
      prompt = "You are a security engineer. Audit code and infrastructure for vulnerabilities. Check for injection, auth bypass, data exposure, insecure defaults, and dependency risks. Produce findings with CVSS-style severity, reproduction steps, and remediation guidance.",
      tools = "read_file,list_directory,run_sh,search_web,fetch_url",
      temperature = 0.2f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-writer".toByteArray()).toString(),
      name = "Writer",
      prompt = "You are a technical writer. Produce clear documentation, READMEs, API docs, user guides, and architecture decision records. Match the project's existing documentation style. Be concise but thorough. Use proper markdown formatting.",
      tools = "read_file,list_directory,write_file,search_web,fetch_url",
      temperature = 0.6f,
      builtin = true,
    ),
    AgentEntity(
      id = UUID.nameUUIDFromBytes("builtin-reviewer".toByteArray()).toString(),
      name = "Reviewer",
      prompt = "You are a senior code reviewer. Review changes for correctness, style, performance, and maintainability. Identify issues but also acknowledge good patterns. Be constructive — suggest specific improvements. Approve when quality is met, request changes with clear rationale when not.",
      tools = "read_file,list_directory,search_web,fetch_url",
      temperature = 0.4f,
      builtin = true,
    ),
  )
}
