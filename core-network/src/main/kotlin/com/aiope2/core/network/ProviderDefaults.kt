package com.aiope2.core.network

object ProviderDefaults {
  val providers = listOf(
    LlmProvider("pollinations", "https://text.pollinations.ai/openai",
      defaultModel = "openai-fast",
      staticModels = listOf("openai", "openai-large", "openai-fast")),
    LlmProvider("github-models", "https://models.github.ai/inference",
      defaultModel = "gpt-4o-mini"),
    LlmProvider("google-ai-studio", "https://generativelanguage.googleapis.com/v1beta/openai",
      defaultModel = "models/gemini-2.0-flash"),
    LlmProvider("openrouter", "https://openrouter.ai/api/v1",
      defaultModel = "deepseek/deepseek-chat-v3-0324:free"),
    LlmProvider("bedrock-mantle", "https://bedrock-mantle.us-west-2.api.aws/v1",
      defaultModel = "anthropic.claude-sonnet-4-20250514-v1:0"),
    LlmProvider("cohere", "https://api.cohere.ai/compatibility/v1",
      defaultModel = "command-r"),
    LlmProvider("cloudflare", "https://api.cloudflare.com/client/v4/accounts/ACCOUNT_ID/ai/v1",
      defaultModel = "@cf/meta/llama-3.1-8b-instruct"),
    LlmProvider("cline", "https://api.cline.bot/api/v1",
      defaultModel = "kwaipilot/kat-coder-pro",
      staticModels = listOf("kwaipilot/kat-coder-pro", "minimax/minimax-m2.5", "z-ai/glm-5")),
    LlmProvider("zen", "https://opencode.ai/zen/v1",
      defaultModel = "anthropic/claude-sonnet-4-20250514"),
    LlmProvider("openai", "https://api.openai.com/v1",
      defaultModel = "gpt-4o-mini"),
    LlmProvider("anthropic", "https://api.anthropic.com/v1",
      defaultModel = "claude-sonnet-4-20250514"),
    LlmProvider("deepseek", "https://api.deepseek.com/v1",
      defaultModel = "deepseek-chat"),
  )
}
