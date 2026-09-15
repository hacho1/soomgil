"""Recover only public tourism records from a local MariaDB dump."""
import json
import argparse
import math
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--crowd-only", action="store_true", help="Export valid archived crowd records without changing the place catalog")
args = parser.parse_args()
dump = (root / "backup/soomgil_db_latest.sql").read_text(encoding="utf-8")
statement = re.search(r"INSERT INTO `places` VALUES\s*\n.*?;", dump, re.DOTALL).group()
columns = ["id", "address", "category", "category_label", "concentration", "hidden_score", "image", "level", "mapx", "mapy", "name", "approved", "source"]
connection = sqlite3.connect(":memory:")
connection.row_factory = sqlite3.Row
connection.execute("CREATE TABLE places (" + ", ".join(columns) + ")")
connection.execute(statement)
records = []
crowd_records = []
for row in connection.execute("SELECT * FROM places"):
    place = dict(row)
    if place["source"] in ("test", "submission", "fallback") or not place["mapx"] or not place["mapy"]:
        continue
    if not (124 < float(place["mapx"]) < 132 and 32 < float(place["mapy"]) < 39):
        continue
    category = place["category"] or ("cafe" if "커피" in place["name"] or "카페" in place["name"] or place["name"] == "곳" else "food" if "횟집" in place["name"] else "tour")
    records.append({key: place[key] for key in ("name", "address", "image", "mapx", "mapy")} | {"category": category})
    try:
        concentration = float(place["concentration"])
    except (ValueError, TypeError):
        continue
    # The old DB also used 999 for unavailable data; retain only usable values and their original labels.
    if math.isfinite(concentration) and 0 <= concentration <= 100 and place["level"] in ("한적함", "보통", "붐빔"):
        crowd_records.append({key: place[key] for key in ("name", "address", "mapx", "mapy", "source")} | {
            "concentration": concentration, "level": place["level"], "collectedAt": None
        })
target = root / "backend/src/main/resources/data/recovered-places.json"
target.parent.mkdir(parents=True, exist_ok=True)
if not args.crowd_only:
    target.write_text(json.dumps(records, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Recovered {len(records)} public place records; no account data exported.")
backup_time = re.search(r"-- Dump completed on\s+(\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2})", dump)
crowd_data = {"source": "legacy-db", "backupAt": backup_time.group(1).replace(" ", "T") if backup_time else None, "records": crowd_records}
(target.parent / "recovered-crowd.json").write_text(json.dumps(crowd_data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(f"Recovered {len(crowd_records)} archived crowd records; collection dates remain unknown.")
