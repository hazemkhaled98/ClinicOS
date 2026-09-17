import json
import re
import sys

data = json.load(sys.stdin)
tool_input = data.get("tool_input", {})
file_path = tool_input.get("file_path", "")

if not file_path.endswith(".java") or file_path.endswith("TenantConnectionListener.java"):
    sys.exit(0)

content = tool_input.get("content") or tool_input.get("new_string") or ""
if not content:
    sys.exit(0)

raw_jdbc = re.search(r"\b(DriverManager\.getConnection|java\.sql\.Connection\b|\.createStatement\(|\.prepareStatement\()", content)
if raw_jdbc and "DSLContext" not in content:
    print(json.dumps({
        "systemMessage": (
            "Raw JDBC pattern detected outside TenantConnectionListener in "
            f"{file_path}. CLAUDE.md rule: every transaction must SET LOCAL app.clinic_id "
            "before any query, or RLS silently returns empty results. Use DSLContext / "
            "@Transactional instead, or confirm this path binds TenantContext first."
        )
    }))

sys.exit(0)
