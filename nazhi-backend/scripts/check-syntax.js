import { readdirSync } from "node:fs";
import { join } from "node:path";
import { spawnSync } from "node:child_process";

function checkDirectory(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
    const path = join(directory, entry.name);
    if (entry.isDirectory()) {
      checkDirectory(path);
    } else if (entry.name.endsWith(".js")) {
      const result = spawnSync(process.execPath, ["--check", path], { stdio: "inherit" });
      if (result.error) throw result.error;
      if (result.status !== 0) process.exit(result.status || 1);
    }
  }
}

checkDirectory("src");
checkDirectory("scripts");
