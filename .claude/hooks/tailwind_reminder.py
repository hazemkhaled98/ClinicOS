import json
import sys

data = json.load(sys.stdin)
tool_input = data.get("tool_input", {})
file_path = (
    data.get("tool_response", {}).get("filePath")
    or tool_input.get("file_path", "")
)

normalized = file_path.replace("\\", "/")
if "/templates/" in normalized or normalized.endswith("components.css"):
    print(json.dumps({
        "systemMessage": (
            f"{file_path} changed — run `npm run build:css` (apps/api) to rebuild "
            "target/classes/static/css/app.css before testing in the browser."
        )
    }))

sys.exit(0)
