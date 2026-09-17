import type { Plugin } from "@opencode-ai/plugin"

const RAW_JDBC = /\b(DriverManager\.getConnection|java\.sql\.Connection\b|\.createStatement\(|\.prepareStatement\()/

const tenantGuard: Plugin = async () => {
  return {
    "tool.execute.before": async (input, output) => {
      const tool = String(input?.tool ?? "").toLowerCase()
      if (tool !== "edit" && tool !== "write") return

      const args = output?.args ?? {}
      const filePath = String(args.filePath ?? "").replaceAll("\\", "/")
      if (filePath.endsWith("TenantConnectionListener.java")) return
      if (!filePath.endsWith(".java")) return

      const content = String(args.content ?? args.newString ?? "")
      if (!content) return
      if (!RAW_JDBC.test(content)) return
      if (content.includes("DSLContext")) return

      throw new Error(
        `CLINICOS TENANT GUARD: raw JDBC pattern detected in ${args.filePath}. ` +
          "Every transaction must set app.clinic_id via TenantContext before any query, " +
          "or RLS silently returns empty results. Use DSLContext / @Transactional instead, " +
          "or confirm this path binds TenantContext first.",
      )
    },
  }
}

export default tenantGuard