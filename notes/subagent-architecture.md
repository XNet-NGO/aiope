# Subagent Architecture Notes

## Async Approach (v1 — works, replaced)

The async subagent approach was implemented and verified working:
- SubagentManager spawns background coroutines per task
- Stage tracking: SEARCHING → READING → SUMMARIZING → FINISHED
- onTaskFinished injects system message with results
- Auto-triggers main agent to process findings when not streaming
- Subagent cards with animated stage indicators in chat stream

This approach works but has downsides:
- Complex handoff (system message injection + auto-trigger send)
- Main agent may not properly incorporate results
- Messy output — raw results dumped into chat
- No natural way for parent to synthesize multiple subagent results

## Blocking Approach (v2 — opencode-style, current)

Adopted from opencode's architecture:
- `task` tool is a regular blocking tool call
- Subagent runs to completion, result returned as tool output
- Parent agent gets `<task_result>` in its tool results
- Parent decides what to show the user — clean synthesis
- Multiple concurrent subagents via PARALLEL_SAFE + parallel tool execution
- Subagent cards render inside tool tabs area, not as separate messages
- Parent's text output naturally comes after all subagents finish

Key insight: the orchestrator already handles parallel tool calls. When the model
returns multiple `task(...)` calls in one response, they all run concurrently.
The orchestrator waits for ALL results before sending them back to the model.
