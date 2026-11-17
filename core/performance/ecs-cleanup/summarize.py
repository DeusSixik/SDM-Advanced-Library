"""Generate the checked-in comparison table from raw JMH JSON, preserving confidence intervals."""
from pathlib import Path
import json

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
raw = json.loads((HERE / "jmh-final.json").read_text(encoding="utf-8"))
delta = json.loads((HERE / "jmh-delta-final.json").read_text(encoding="utf-8"))
delta_read = json.loads((HERE / "jmh-delta-read.json").read_text(encoding="utf-8"))
raw.extend(delta)
raw.extend(delta_read)
groups = {}
for result in raw:
    method = result["benchmark"].rsplit(".", 1)[1]
    groups.setdefault(result["params"]["workload"], {})[method] = result

rows = []
table = [
    "| Сценарий | До, мкс | После, мкс | Изменение времени | До, B/op | После, B/op |",
    "| --- | ---: | ---: | ---: | ---: | ---: |",
]
for workload, pair in groups.items():
    assert set(pair) == {"before", "after"}, workload
    before, after = pair["before"], pair["after"]
    old, new = before["primaryMetric"], after["primaryMetric"]
    assert old["scoreUnit"] == new["scoreUnit"] == "us/op"
    old_alloc = before["secondaryMetrics"]["gc.alloc.rate.norm"]
    new_alloc = after["secondaryMetrics"]["gc.alloc.rate.norm"]
    change = 100 * (new["score"] / old["score"] - 1)
    ratio = old["score"] / new["score"]
    rows.append({
        "workload": workload,
        "before_us": old["score"], "after_us": new["score"],
        "before_error_99_9_us": old["scoreError"], "after_error_99_9_us": new["scoreError"],
        "time_change_percent": change, "speedup": ratio,
        "before_bytes": old_alloc["score"], "after_bytes": new_alloc["score"],
        "confidence_intervals_overlap": max(old["scoreConfidence"][0], new["scoreConfidence"][0])
            <= min(old["scoreConfidence"][1], new["scoreConfidence"][1]),
    })
    table.append(f"| `{workload}` | {old['score']:.3f} ± {old['scoreError']:.3f} | "
                 f"{new['score']:.3f} ± {new['scoreError']:.3f} | {change:+.1f}% | "
                 f"{old_alloc['score']:.1f} | {new_alloc['score']:.1f} |")

assert len(rows) == 16, len(rows)
(HERE / "summary.json").write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
report = ROOT / "ECS-PERFORMANCE.md"
text = report.read_text(encoding="utf-8")
start, end = "<!-- RESULTS:START -->", "<!-- RESULTS:END -->"
replacement = "\n" + "\n".join(table) + "\n\n"
replacement += ("Отрицательный процент означает уменьшение времени операции. `±` — оценка JMH "
                "для 99,9% интервала. Десятые/сотые B/op около нуля включают измерительный фон. "
                "Сырые результаты: [jmh-final.json](performance/ecs-cleanup/jmh-final.json), "
                "сводка: [summary.json](performance/ecs-cleanup/summary.json).\n")
text = text[:text.index(start) + len(start)] + replacement + text[text.index(end):]
report.write_text(text, encoding="utf-8", newline="\n")
for row in rows:
    print(f"{row['workload']:20} {row['before_us']:9.3f} -> {row['after_us']:9.3f} us; "
          f"{row['speedup']:5.2f}x; {row['before_bytes']:10.1f} -> {row['after_bytes']:10.1f} B/op")
