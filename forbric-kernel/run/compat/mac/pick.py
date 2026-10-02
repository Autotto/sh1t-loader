#!/usr/bin/env python3
"""Select 100 unseen projects: up to 38 popular from the top 200, then random projects; dependencies are extra.
Usage: pick.py <data-dir> <seed> <exclude-manifest> ...
"""
import io, json, random, sys, tomllib, urllib.error, urllib.parse, zipfile
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import api as p
from archive import jar_ids

DATA = Path(sys.argv[1]).resolve()
SEED = int(sys.argv[2])
EXCLUDE = [Path(x) for x in sys.argv[3:]]
rng = random.Random(SEED)
IGNORE = {'minecraft', 'java', 'fabricloader', 'fabric-loader', 'neoforge', 'forge', 'quilt_loader', 'fml', 'javafml',
          'lowcodefml'}


def main():
    DATA.mkdir(parents=True, exist_ok=True)
    excluded_pids = set()
    for m in EXCLUDE:
        for r in json.loads(m.read_text()):
            if r.get('project_id'):
                excluded_pids.add(r['project_id'])
    selected, picked = {}, set()
    incompatible = set()

    def add(slug, pid, loader, version, kind, needed_by=''):
        key = (pid, loader)
        if key in selected:
            return False
        a = p.primary(version)
        fname = p.safe_filename(a['filename'])
        if fname.casefold() in {r['filename'].casefold() for r in selected.values()}:
            fname = f'{loader}-{fname}'
        selected[key] = dict(kind=kind, slug=slug, project_id=pid, loader=loader, version=version['version_number'],
                             version_id=version['id'], version_type=version.get('version_type'), loaders=version['loaders'],
                             filename=fname, source_filename=a['filename'], url=a['url'],
                             size=a['size'], sha1=a['hashes']['sha1'], needed_by=needed_by)
        for d in version.get('dependencies', []):
            if d.get('dependency_type') == 'incompatible' and d.get('project_id'):
                incompatible.add(d['project_id'])
        print(f'  + {kind:11} {slug} [{loader}] {version["version_number"]}' + (f'  (for {needed_by})' if needed_by else ''), flush=True)
        return True

    def pick(hits, want, kind):
        got = 0
        for h in hits:
            if got == want:
                break
            pid, slug = h['project_id'], h['slug']
            if pid in picked or pid in excluded_pids:
                continue
            builds = p.loader_builds(pid)
            if not builds:
                continue
            loader = rng.choice(sorted(builds))
            mine = {d.get('project_id') for d in builds[loader].get('dependencies', []) if d.get('dependency_type') == 'incompatible'}
            picked.add(pid)
            got += add(slug, pid, loader, builds[loader], kind)
        return got

    top = []
    for off in (0, 100):
        top += p.get('/search', facets=p.FACETS, index='downloads', limit=100, offset=off)['hits']
    rng.shuffle(top)
    (DATA / 'pool.json').write_text(json.dumps(dict(seed=SEED, popular=top), indent=1) + '\n')
    print('popular (top 200 by downloads):')
    n_pop = pick(top, min(38, sum(h['project_id'] not in excluded_pids for h in top)), 'popular')
    wanted_random = 100 - n_pop
    total = p.get('/search', facets=p.FACETS, limit=1)['total_hits']
    offsets = list(range(0, min(total, 10000), 100))
    rng.shuffle(offsets)
    pool = []
    for off in offsets[:15]:
        pool += p.get('/search', facets=p.FACETS, index='newest', limit=100, offset=off)['hits']
    rng.shuffle(pool)
    saved_pool = json.loads((DATA / 'pool.json').read_text())
    saved_pool.update(random=pool, total=total)
    (DATA / 'pool.json').write_text(json.dumps(saved_pool, indent=1) + '\n')
    print(f'random (pool {len(pool)} of {total}):')
    n_rand = pick(pool, wanted_random, 'random')

    def download(row):
        target = DATA / 'mods' / row['filename']
        if not (target.is_file() and target.stat().st_size == row['size'] and p.sha1(target) == row['sha1']):
            target.write_bytes(p.fetch(row['url']))
            if target.stat().st_size != row['size'] or p.sha1(target) != row['sha1']:
                raise SystemExit('hash/size mismatch ' + row['filename'])
        row['sha1_ok'] = True

    (DATA / 'mods').mkdir(parents=True, exist_ok=True)
    queue, done = list(selected.values()), set()
    unresolved = {}
    while queue:
        row = queue.pop(0)
        if row['filename'] in done:
            continue
        done.add(row['filename'])
        download(row)
        version = next(v for v in p.versions(row['project_id']) if v['id'] == row['version_id'])
        # Modrinth's required dependencies, for the dependent's loader
        for d in version.get('dependencies', []):
            if d.get('dependency_type') != 'required':
                continue
            pid, vid, dep = d.get('project_id'), d.get('version_id'), None
            if vid:
                dep = p.get('/version/' + urllib.parse.quote(vid, safe=''))
                pid = dep['project_id']
                if p.MC not in dep.get('game_versions', []) or not p.primary(dep):
                    dep = None
            if not pid or (pid, row['loader']) in selected:
                continue
            dep = dep or p.loader_builds(pid).get(row['loader'])
            if dep is None:
                unresolved.setdefault(row['slug'], []).append('modrinth:' + pid)
                continue
            if add(p.get('/project/' + pid)['slug'], pid, row['loader'], dep, 'dep', row['slug']):
                queue.append(selected[(pid, row['loader'])])
        # required mod ids from the jar itself that nothing selected provides
        provided = set()
        for other in selected.values():
            f = DATA / 'mods' / other['filename']
            if f.exists():
                provided |= jar_ids(f.read_bytes())[0]
        _, req = jar_ids((DATA / 'mods' / row['filename']).read_bytes())
        for mid in sorted(req - IGNORE - provided):
            if mid.startswith('fabric-') and any(s['slug'] == 'fabric-api' for s in selected.values()):
                continue
            found = None
            aliases = {'fabric': 'fabric-api', 'cloth_config': 'cloth-config', 'cloth_config2': 'cloth-config', 'kotlinforforge': 'kotlin-for-forge'}
            candidates = ['fabric-api'] if mid.startswith('fabric-') else [aliases.get(mid, mid), mid, mid.replace('_', '-')]
            for cand in dict.fromkeys(candidates):
                try:
                    proj = p.get('/project/' + urllib.parse.quote(cand, safe=''))
                except urllib.error.HTTPError:
                    continue
                b = p.loader_builds(proj['id']).get(row['loader'])
                if b:
                    found = (proj, b)
                    break
            if not found:
                unresolved.setdefault(row['slug'], []).append('id:' + mid)
                continue
            proj, b = found
            if add(proj['slug'], proj['id'], row['loader'], b, 'missing-dep', row['slug']):
                queue.append(selected[(proj['id'], row['loader'])])
    manifest = list(selected.values())
    names = {}
    for r in manifest:
        if r['filename'].casefold() in names:
            raise SystemExit('filename collision ' + r['filename'])
        names[r['filename'].casefold()] = r['slug']
    (DATA / 'manifest.json').write_text(json.dumps(manifest, indent=1) + '\n')
    (DATA / 'unresolved.json').write_text(json.dumps(unresolved, indent=1) + '\n')
    from collections import Counter
    print('total jars', len(manifest), Counter(r['kind'] for r in manifest), Counter(r['loader'] for r in manifest))
    print('unresolved', unresolved)
    return 0 if n_pop + n_rand == 100 else 2


if __name__ == '__main__':
    sys.exit(main())
