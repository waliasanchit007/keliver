"""python3 -I pidof.py <processes.json> <AppName.app>: the pid of that app's process, if running."""
import json
import sys

for p in json.load(open(sys.argv[1]))["result"]["runningProcesses"]:
    if "/" + sys.argv[2] + "/" in p.get("executable", ""):
        print(p["processIdentifier"])
        break
