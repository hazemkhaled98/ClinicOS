import { existsSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { Plugin } from "@opencode-ai/plugin"

const EDIT_TOOLS = new Set(["edit", "write", "apply_patch"])
const SCAN_TIMEOUT_MS = 90_000
const HOOK =
  process.platform === "win32"
    ? ".agents/skills/impeccable/scripts/impeccable.cmd"
    : ".agents/skills/impeccable/scripts/impeccable"

const impeccableHooks: Plugin = async ({ directory, $ }) => {
  const script = join(directory, HOOK)
  if (!existsSync(script)) return {}

  const shell = $.env({ ...process.env, IMPECCABLE_HOOK_QUIET: "1" })

  // The engine's `hook` verb reads a Claude/Codex hook event as JSON on stdin.
  // ponytail: Bun 1.4's shell exposes no stdin stream, so stage the event in a
  // temp file and redirect it in. No session Stop deep pass: the 0.1.5 engine
  // returns nothing for Stop events, only per-edit PostToolUse findings.
  const run = async (event: string): Promise<string> => {
    const dir = mkdtempSync(join(tmpdir(), "impeccable-hook-"))
    const file = join(dir, "event.json")
    writeFileSync(file, event)
    try {
      return await Promise.race([
        shell`"${script}" hook < "${file}"`.cwd(directory).quiet().nothrow().text(),
        new Promise<string>((resolve) => setTimeout(() => resolve(""), SCAN_TIMEOUT_MS)),
      ])
    } catch {
      return ""
    } finally {
      rmSync(dir, { recursive: true, force: true })
    }
  }

  return {
    "tool.execute.after": async (input, output) => {
      const tool = String(input?.tool ?? "").toLowerCase()
      if (!EDIT_TOOLS.has(tool)) return
      const filePath = input?.args?.filePath ?? input?.args?.path
      if (typeof filePath !== "string" || !filePath) return
      const event = JSON.stringify({
        hook_event_name: "PostToolUse",
        tool_name: tool,
        session_id: input?.sessionID ?? "opencode",
        cwd: directory,
        tool_input: { file_path: filePath },
      })
      const stdout = await run(event)
      if (!stdout.trim()) return
      try {
        const reminder = JSON.parse(stdout)?.hookSpecificOutput?.additionalContext
        if (typeof reminder === "string" && reminder) {
          output.output = `${output.output ?? ""}\n\n${reminder}`
        }
      } catch {
        // engine stdout was not the expected reminder JSON; ignore.
      }
    },
  }
}

export default impeccableHooks