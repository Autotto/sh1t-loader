#!/usr/bin/env python3
"""With no mods, lava and water must react on Forbric exactly as they do on vanilla 26.2.

    fluid-parity-gate.py [--output DIR] [--unfixed]

Runs one datapack, unchanged, on two dedicated servers in turn: pure vanilla (launch-vanilla-server.sh, the player's own
26.2 jar, as gate-m31 runs it) and the kernel with zero mods (launch-kernel-server.sh). The datapack builds every case
in the air over a superflat world, each inside its own stone shell, and classifies the cells into scores; the console is
asked for the scores, the world is saved, and the saved region files are read back. PASS needs both servers to save
and stop, vanilla to show the reactions the scenario is about (so a scenario that measured nothing cannot pass), and
Forbric to match vanilla on every score and every block in the build box.

Cases (z=8, y=80): A lava source set beside water -> obsidian; B flowing lava set beside water -> cobblestone; C water
set beside lava (neighborChanged) -> obsidian; D lava set on soul soil beside blue ice -> basalt; E lava flows into a
cell under a waterlogged slab -> cobblestone; F water flows to a lava source -> obsidian; G and H a cobblestone and a
basalt generator, each cell emptied every tick for 600 ticks (vanilla makes 20 of each); I lava flows down into water
-> stone. A, B, D, E, G and H are LiquidBlock.onPlace; C and F neighborChanged; I LavaFluid.spreadTo.

TEETH (recorded 2026-10-02, kernel jar 67aede69): --unfixed runs the kernel with -Dforbric.fluidInteractions=off, which puts
back the neuter of MinecraftForge's FluidInteractionRegistry.canInteract that the merged onPlace asks. Then A and B stay
lava and are washed over by water, D and E stay lava, both generators make nothing — 14 scores and 6 cells differ from
vanilla, and this gate is RED. Only C, F and I (paths that never asked that registry) still match.
"""
import argparse
from datetime import datetime
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import threading
import time

Y, Z = 80, 8
CODES = ['air', 'lava', 'water', 'obsidian', 'cobblestone', 'stone', 'basalt', 'blue_ice', 'soul_soil', 'stone_slab']
CELLS = {'A': (4, Y), 'B': (12, Y), 'C': (20, Y), 'D': (28, Y), 'E': (37, Y), 'F': (44, Y), 'G': (53, Y), 'H': (61, Y),
         'I': (68, Y), 'Isrc': (68, Y + 1), 'Gsrc': (52, Y), 'Hsrc': (60, Y)}
# What vanilla must show for the scenario to be measuring anything (the generators must also produce, see below).
VANILLA = {'A': 'obsidian', 'B': 'cobblestone', 'C': 'obsidian', 'D': 'basalt', 'E': 'cobblestone', 'F': 'obsidian',
           'I': 'stone', 'A_end': 'obsidian', 'B_end': 'cobblestone', 'D_end': 'basalt', 'E_end': 'cobblestone'}
GENERATORS = ['g_cobblestone', 'g_obsidian', 'g_stone', 'g_basalt', 'g_mined', 'h_basalt', 'h_cobblestone', 'h_obsidian',
              'h_stone', 'h_mined']
BOX = ((0, 76, 0), (127, 86, 15))
PROPERTIES = ('server-ip=127.0.0.1\nserver-port={port}\nlevel-name=world\nlevel-type=minecraft:flat\nlevel-seed=fluidparity\n'
              'generate-structures=false\nonline-mode=false\nmax-tick-time=-1\npause-when-empty-seconds=0\n'
              'view-distance=2\nsimulation-distance=2\nspawn-protection=0\nsync-chunk-writes=true\n')


def shell(x0, x1, top):
    return f'fill {x0} {Y - 1} {Z - 1} {x1} {top} {Z + 1} minecraft:stone'


def classify(score, x, y):
    lines = [f'scoreboard players set #{score} fp -1']
    lines += [f'execute if block {x} {y} {Z} minecraft:{block} run scoreboard players set #{score} fp {code}'
              for code, block in enumerate(CODES)]
    return lines


def functions():
    slab = 'minecraft:stone_slab[type=bottom,waterlogged=true]'
    build = ['say [fluidparity] building',
             shell(3, 6, Y + 1), f'setblock 4 {Y} {Z} air', f'setblock 5 {Y} {Z} water', f'setblock 4 {Y} {Z} lava',
             shell(11, 14, Y + 1), f'setblock 12 {Y} {Z} air', f'setblock 13 {Y} {Z} water', f'setblock 12 {Y} {Z} lava[level=1]',
             shell(19, 22, Y + 1), f'setblock 20 {Y} {Z} air', f'setblock 21 {Y} {Z} air', f'setblock 20 {Y} {Z} lava',
             f'setblock 21 {Y} {Z} water',
             shell(27, 30, Y + 1), f'setblock 28 {Y - 1} {Z} soul_soil', f'setblock 29 {Y} {Z} blue_ice', f'setblock 28 {Y} {Z} air',
             f'setblock 28 {Y} {Z} lava',
             shell(35, 38, Y + 2), f'setblock 37 {Y + 1} {Z} {slab}', f'setblock 37 {Y} {Z} air', f'setblock 36 {Y} {Z} lava',
             shell(43, 47, Y + 1), f'setblock 45 {Y} {Z} air', f'setblock 44 {Y} {Z} lava', f'setblock 46 {Y} {Z} water',
             shell(51, 54, Y + 2), f'setblock 53 {Y + 1} {Z} {slab}', f'setblock 53 {Y} {Z} air', f'setblock 52 {Y} {Z} lava',
             shell(59, 63, Y + 1), f'setblock 61 {Y - 1} {Z} soul_soil', f'setblock 62 {Y} {Z} blue_ice', f'setblock 61 {Y} {Z} air',
             f'setblock 60 {Y} {Z} lava',
             shell(67, 69, Y + 2), f'setblock 68 {Y} {Z} water', f'setblock 68 {Y + 1} {Z} lava']
    for case in 'ABCD':
        build += classify(case, *CELLS[case])
    check = ['say [fluidparity] checking'] + classify('E', *CELLS['E']) + classify('F', *CELLS['F']) + classify('I', *CELLS['I'])
    end = ['say [fluidparity] final state']
    for case, (x, y) in CELLS.items():
        end += classify(case + '_end', x, y)
    end.append('say FLUIDPARITY DONE')
    mine = []
    for gen, x in (('g', 53), ('h', 61)):
        for block in ('cobblestone', 'obsidian', 'stone', 'basalt'):
            mine.append(f'execute if block {x} {Y} {Z} minecraft:{block} run scoreboard players add #{gen}_{block} fp 1')
        solid = f'execute unless block {x} {Y} {Z} minecraft:air unless block {x} {Y} {Z} minecraft:lava run'
        mine += [f'{solid} scoreboard players add #{gen}_mined fp 1', f'{solid} setblock {x} {Y} {Z} minecraft:air']
    tick = ['execute if loaded 0 80 0 if loaded 127 80 15 run scoreboard players add #t fp 1',
            'execute if score #t fp matches 20 run function fluidparity:build',
            'execute if score #t fp matches 21..620 run function fluidparity:mine',
            'execute if score #t fp matches 100 run function fluidparity:check',
            'execute if score #t fp matches 640 run function fluidparity:end']
    load = ['scoreboard objectives add fp dummy', 'scoreboard players set #t fp 0', 'forceload add 0 0 127 15']
    return dict(load=load, tick=tick, build=build, check=check, mine=mine, end=end)


def scores():
    return ['A', 'B', 'C', 'D', 'E', 'F', 'I'] + [case + '_end' for case in CELLS] + GENERATORS


def stage(run, port):
    run.mkdir(parents=True)
    (run / 'mods').mkdir()                      # genuinely zero-mod on the Forbric side
    (run / 'eula.txt').write_text('eula=true\n')
    (run / 'server.properties').write_text(PROPERTIES.format(port=port))
    pack = run / 'world/datapacks/fluidparity'
    (pack / 'data/fluidparity/function').mkdir(parents=True)
    (pack / 'data/minecraft/tags/function').mkdir(parents=True)
    (pack / 'pack.mcmeta').write_text(json.dumps({'pack': {'description': 'Forbric fluid parity', 'min_format': [107, 0], 'max_format': 107}}))
    for name, body in functions().items():
        (pack / f'data/fluidparity/function/{name}.mcfunction').write_text('\n'.join(body) + '\n')
    (pack / 'data/minecraft/tags/function/load.json').write_text(json.dumps({'values': ['fluidparity:load']}))
    (pack / 'data/minecraft/tags/function/tick.json').write_text(json.dumps({'values': ['fluidparity:tick']}))


def boot(launcher, run, env):
    """Start a server, wait for the datapack's last line, ask for every score, save, stop. The console's lines."""
    lines, done = [], threading.Event()
    with (run / 'console.log').open('w') as console:
        process = subprocess.Popen([str(launcher)], env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)

        def pump():
            for line in process.stdout:
                console.write(line)
                console.flush()
                lines.append(line)
                if 'FLUIDPARITY DONE' in line:
                    done.set()
        reader = threading.Thread(target=pump, daemon=True)
        reader.start()
        deadline = time.time() + 600
        while not done.is_set() and process.poll() is None and time.time() < deadline:
            done.wait(1)
        if process.poll() is None:
            for score in scores():
                process.stdin.write(f'scoreboard players get #{score} fp\n')
            process.stdin.write('save-all flush\n')
            process.stdin.flush()
            time.sleep(8)
            process.stdin.write('stop\n')
            process.stdin.flush()
            try:
                process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, 9)
                process.wait()
        reader.join(timeout=10)
    return ''.join(lines), done.is_set(), process.returncode


def read_scores(text):
    raw = {m.group(1): int(m.group(2)) for m in re.finditer(r'#([A-Za-z0-9_]+) has (-?\d+) \[fp\]', text)}
    named = {}
    for name in scores():
        value = raw.get(name)
        if name in GENERATORS:
            named[name] = value or 0                 # nothing ever added: the score is unset
        else:
            named[name] = CODES[value] if value is not None and 0 <= value < len(CODES) else value
    return named


def read_blocks(region):
    spec = importlib.util.spec_from_file_location('world_parity', Path(__file__).with_name('world-parity.py'))
    parity = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(parity)
    (x0, y0, z0), (x1, y1, z1) = BOX
    cells = {}
    for cx, cz, root in parity.chunks(region):
        if cx * 16 > x1 or cx * 16 + 15 < x0 or cz * 16 > z1 or cz * 16 + 15 < z0:
            continue
        for section in root.get('sections', []):
            sy = section.get('Y')
            states = section.get('block_states') or {}
            palette = states.get('palette') or []
            if sy is None or sy * 16 > y1 or sy * 16 + 15 < y0 or not palette:
                continue
            bits = max(4, (len(palette) - 1).bit_length()) if len(palette) > 1 else 0
            for index in range(4096):
                x, y, z = cx * 16 + (index & 15), sy * 16 + (index >> 8), cz * 16 + ((index >> 4) & 15)
                if not (x0 <= x <= x1 and y0 <= y <= y1 and z0 <= z <= z1):
                    continue
                if bits:
                    per_long = 64 // bits
                    word = states['data'][index // per_long] % (1 << 64)
                    entry = palette[(word >> ((index % per_long) * bits)) & ((1 << bits) - 1)]
                else:
                    entry = palette[0]
                if entry['Name'] != 'minecraft:air':
                    props = entry.get('Properties') or {}
                    cells[f'{x},{y},{z}'] = entry['Name'] + ('[' + ','.join(f'{k}={props[k]}' for k in sorted(props)) + ']' if props else '')
    return cells


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--output', type=Path, help='A new directory for logs, worlds and the report')
    parser.add_argument('--unfixed', action='store_true', help='Run the kernel with -Dforbric.fluidInteractions=off (must go RED)')
    args = parser.parse_args()
    kernel = Path(__file__).resolve().parents[2]
    output = (args.output or kernel / 'build/verification' / ('fluid-parity-' + datetime.now().strftime('%Y%m%d-%H%M%S'))).resolve()
    output.mkdir(parents=True, exist_ok=False)
    port = 25540 + os.getpid() % 200
    arms = {}
    for arm, launcher, extra in (('vanilla', kernel / 'run/launch-vanilla-server.sh', {}),
                                 ('forbric', kernel / 'run/launch-kernel-server.sh',
                                  {'FORBRIC_JVM': '-Xmx2G' + (' -Dforbric.fluidInteractions=off' if args.unfixed else ''),
                                   'FORBRIC_COMPAT_POLICY': 'strict'})):
        run = output / arm
        stage(run, port)
        port += 1
        env = dict(os.environ, RUNDIR=str(run), **extra)
        print(f'[fluid-parity] {arm}: booting {launcher.name}', flush=True)
        text, finished, code = boot(launcher, run, env)
        region = run / 'world/dimensions/minecraft/overworld/region'
        arms[arm] = dict(finished=finished, exit=code, saved='All dimensions are saved' in text or 'Saved the game' in text,
                         scores=read_scores(text), cells=read_blocks(region) if region.is_dir() else {})
        print(f'[fluid-parity] {arm}: finished={finished} exit={code} saved={arms[arm]["saved"]} '
              f'scores={json.dumps(arms[arm]["scores"])}', flush=True)

    vanilla, forbric = arms['vanilla'], arms['forbric']
    failures = []
    for arm, result in arms.items():
        if not (result['finished'] and result['saved'] and result['exit'] == 0):
            failures.append(f'{arm} did not run the scenario to the end, save and stop')
    wrong = {k: vanilla['scores'].get(k) for k, v in VANILLA.items() if vanilla['scores'].get(k) != v}
    if wrong or not vanilla['scores'].get('g_cobblestone') or not vanilla['scores'].get('h_basalt'):
        failures.append(f'vanilla did not show the reactions the scenario measures: {wrong} '
                        f'generators={vanilla["scores"].get("g_cobblestone")}/{vanilla["scores"].get("h_basalt")}')
    differing_scores = sorted(k for k in scores() if vanilla['scores'].get(k) != forbric['scores'].get(k))
    differing_cells = sorted(k for k in set(vanilla['cells']) | set(forbric['cells'])
                             if vanilla['cells'].get(k) != forbric['cells'].get(k))
    if not vanilla['cells']:
        failures.append('vanilla saved no blocks in the build box')
    if differing_scores:
        failures.append(f'{len(differing_scores)} score(s) differ from vanilla: '
                        + ', '.join(f'{k} vanilla={vanilla["scores"].get(k)} forbric={forbric["scores"].get(k)}' for k in differing_scores))
    if differing_cells:
        failures.append(f'{len(differing_cells)} block(s) differ from vanilla: '
                        + ', '.join(f'{k} vanilla={vanilla["cells"].get(k)} forbric={forbric["cells"].get(k)}' for k in differing_cells[:12]))
    jar = kernel / 'build/libs/forbric-kernel-0.1.0-SNAPSHOT.jar'
    mc = Path(os.environ.get('MC_DIR', Path.home() / 'Library/Application Support/minecraft'))
    vanilla_jar = Path(os.environ.get('VANILLA_JAR', mc / 'versions/26.2/26.2.jar'))
    summary = dict(unfixed=args.unfixed, passed=not failures, failures=failures,
                   kernel_sha256=hashlib.sha256(jar.read_bytes()).hexdigest() if jar.is_file() else None,
                   vanilla_jar_sha256=hashlib.sha256(vanilla_jar.read_bytes()).hexdigest() if vanilla_jar.is_file() else None,
                   scores={arm: result['scores'] for arm, result in arms.items()},
                   block_counts={arm: {b: list(result['cells'].values()).count(b) for b in sorted(set(result['cells'].values()))}
                                 for arm, result in arms.items()},
                   differing_scores=differing_scores, differing_cells=differing_cells)
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    for failure in failures:
        print(f'[fluid-parity] FAIL {failure}', flush=True)
    print(f'[fluid-parity] {"PASS" if not failures else "RED"}: {output}', flush=True)
    sys.exit(0 if not failures else 1)


if __name__ == '__main__':
    main()
