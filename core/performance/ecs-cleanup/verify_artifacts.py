"""Verify the version matrix and prevent benchmark/baseline classes from leaking into mod JARs."""
from pathlib import Path
from xml.etree import ElementTree as ET
from zipfile import ZipFile
import hashlib
import json

ROOT = Path(__file__).resolve().parents[3]
VERSIONS = ("1.19.2", "1.20.1", "1.21.1", "26.1.2", "26.3")
results = {}
for version in VERSIONS:
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    files = list((ROOT / "core/versions" / version / "build/test-results/test").glob("TEST-*.xml"))
    assert files, version
    for path in files:
        suite = ET.parse(path).getroot()
        for name in totals:
            totals[name] += int(suite.get(name, "0"))
    assert totals["tests"] == 250 and not any(totals[name] for name in ("failures", "errors", "skipped")), (version, totals)
    results[version] = totals

processor = dict(tests=0, failures=0, errors=0, skipped=0)
for path in (ROOT / "ecs-processor/build/test-results/test").glob("TEST-*.xml"):
    suite = ET.parse(path).getroot()
    for name in processor:
        processor[name] += int(suite.get(name, "0"))
assert processor["tests"] == 11 and not processor["failures"] and not processor["errors"]
results["processor"] = processor

new_service = "META-INF/services/dev.sixik.sdmal.api.ecs.WorldObjectComponent"
old_service = "META-INF/services/dev.sixik.sdmal.core.ecs.WorldObjectComponent"
jars = [path for path in (ROOT / "build/libs/1.0.0").glob("sdmal-*.jar")
        if "-sources" not in path.name and "-processor-" not in path.name]
assert len(jars) == 14, len(jars)
for path in jars:
    with ZipFile(path) as archive:
        names = set(archive.namelist())
        assert "dev/sixik/sdmal/api/ecs/WorldObject.class" in names, path.name
        assert new_service in names and old_service not in names, path.name
        assert "dev.sixik.sdmal.example.ecs.GeneratorComponent" in archive.read(new_service).decode(), path.name
        assert not any(name.startswith(("baseline/", "dev/sixik/sdmal/benchmark/", "org/openjdk/jmh/"))
                       or "EcsCleanupBenchmark" in name or "ecs-before-cleanup" in name
                       for name in names), path.name

baseline = ROOT / "core/src/jmh/resources/baseline"
manifest = json.loads((baseline / "manifest.json").read_text(encoding="utf-8"))
for name, expected in manifest["files"].items():
    assert hashlib.sha256((baseline / name).read_bytes()).hexdigest() == expected, name

runtime_hash = hashlib.sha256()
for folder in ("core/src/main/java/dev/sixik/sdmal/api/ecs", "core/src/main/java/dev/sixik/sdmal/core/ecs"):
    for path in sorted((ROOT / folder).rglob("*.java")):
        runtime_hash.update(path.relative_to(ROOT).as_posix().encode())
        runtime_hash.update(b"\0")
        runtime_hash.update(path.read_bytes())
expected_hash = (Path(__file__).parent / "current-source.sha256").read_text().strip()
assert runtime_hash.hexdigest() == expected_hash, "Runtime sources changed after the measured revision."

results["mod_jars"] = len(jars)
results["runtime_source_sha256"] = runtime_hash.hexdigest()
results["baseline_hashes_verified"] = True
results["benchmark_artifacts_excluded"] = True
(Path(__file__).parent / "verification.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print(json.dumps(results, indent=2))
