"""Combine every strictly passing subject with its dependencies, then save and reload the complete pack."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import sweep_verdict
from world_save import saved_since

DATA = Path(os.environ['PERMOD_DATA']).resolve()
TOOLS = Path(__file__).resolve().parent
os.environ['PERMOD_INSTANCE'] = str(DATA / os.environ.get('PERMOD_MIXED_INSTANCE', 'mixed-inst'))
spec = importlib.util.spec_from_file_location('permod', TOOLS / 'per-mod.py')
permod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(permod)
OUT = DATA / os.environ.get('PERMOD_MIXED_OUT', 'mixed')


def run(label, ticks):
    evidence = OUT / label
    evidence.mkdir(parents=True, exist_ok=False)
    start = time.time()
    env = dict(os.environ, SWEEP_WORLD_TICKS=str(ticks), FORBRIC_MC=str(permod.MC),
               FORBRIC_INSTANCE=str(permod.INST), FORBRIC_VERSION=permod.VERSION,
               FORBRIC_JAVA=permod.JAVA, CLIENT_STALL='420', RUN_TIMEOUT='900', GRACE='30')
    with (evidence / 'driver.log').open('w') as log:
        process = subprocess.run([sys.executable, str(TOOLS / 'mac-run.py'), 'run-client-test.py',
                                  '--jvm=-Dforbric.compatibilityPolicy=strict'], env=env, stdout=log, stderr=subprocess.STDOUT)
    for filename in ['client-console.log', '.forbric-kernel/compatibility-report.json', '.forbric-kernel/load-report.txt']:
        source = permod.INST / filename
        if source.exists():
            shutil.copy2(source, evidence / source.name)
    for source in list((permod.INST / 'screenshots').glob('*.png')) + list((permod.INST / 'crash-reports').glob('*.txt')) + list(permod.INST.glob('thread-dump-*.txt')):
        if source.stat().st_mtime >= start:
            shutil.copy2(source, evidence / source.name)
    driver = (evidence / 'driver.log').read_text(errors='replace')
    console = (evidence / 'client-console.log').read_text(errors='replace') if (evidence / 'client-console.log').exists() else ''
    report = json.loads((evidence / 'compatibility-report.json').read_text()) if (evidence / 'compatibility-report.json').exists() else {}
    crashes = list(evidence.glob('crash-*.txt'))
    verdict = sweep_verdict.classify_run(driver, console, crashes)
    bad = sweep_verdict.bad_rows(report)
    missing = sweep_verdict.missing_subjects(subjects, report)
    world = permod.INST / 'saves' / 'compat-world'
    saved = saved_since(world, start)
    strict = sweep_verdict.pack_strict(process.returncode, verdict, report, missing, saved)
    result = dict(run=verdict, strict=strict, bad_mods=bad, missing_subjects=missing, saved=saved, world_ticks=ticks, seconds=int(time.time() - start))
    (evidence / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    print(label, result, flush=True)
    return result


manifest = json.loads((DATA / 'manifest.json').read_text())
all_subjects = {row['filename'] for row in manifest if row['kind'] in ('popular', 'random')}
latest = {}
for line in (DATA / os.environ.get('PERMOD_OUT', 'per-mod') / 'results.jsonl').read_text().splitlines():
    row = json.loads(line)
    latest[row['jar']] = row
if all_subjects - latest.keys():
    raise SystemExit('Individual sweep incomplete; do not omit pending subjects')
sha = permod.kernel_fingerprint()
for name in all_subjects:
    if latest[name].get('kernel_sha256') != sha:
        raise SystemExit('Individual result is from a different kernel: ' + name)
subjects = sorted(name for name in all_subjects if latest[name].get('strict'))
closure = json.loads((DATA / 'closure.json').read_text())
selected = sorted(set(subjects) | {dep for name in subjects for dep in closure[name]})
if not selected:
    raise SystemExit('No strictly passing subjects to combine')
for name in subjects:
    for filename, fingerprint in latest[name]['input_sha256'].items():
        if hashlib.sha256((DATA / 'mods' / filename).read_bytes()).hexdigest() != fingerprint:
            raise SystemExit('Test input changed: ' + filename)
OUT.mkdir(parents=True, exist_ok=False)
(OUT / 'manifest.json').write_text(json.dumps([row for row in manifest if row['filename'] in selected], indent=2) + '\n')
permod.prepare(selected)
# Other test windows must not pause this five-minute mixed world when they take focus.
options = permod.INST / 'options.txt'
options.write_text(options.read_text().replace('pauseOnLostFocus:true', 'pauseOnLostFocus:false'))
first = run('first-load', 6000)
second = dict(run='NOT_RUN', strict=False)
if first['strict']:
    for filename in ['compatibility-report.json', 'load-report.txt']:
        (permod.INST / '.forbric-kernel' / filename).unlink(missing_ok=True)
    second = run('reload', 200)
if permod.kernel_fingerprint() != sha:
    raise SystemExit('Kernel changed during mixed test')
result = dict(kernel_sha256=sha, subjects=subjects, jars=selected, first=first, reload=second, strict=first['strict'] and second['strict'])
(OUT / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
sys.exit(0 if result['strict'] else 1)
