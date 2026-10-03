"""fabric-ab.py: pair verdicts, the session cache, the Forbric-arm minimiser and summary.json, against a fake server."""
import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location("fabric_ab", Path(__file__).with_name("fabric-ab.py"))
ab = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ab)
nc = ab.nc

KERNEL, LAUNCHER = "k" * 64, "l" * 64


def result(engine, outcome, signature=None, digest="x"):
    return dict(engine=engine, outcome=outcome, signature=signature, modSetSha256=digest)


class FakeServer:
    """Forbric fails with SIGNATURE whenever a.jar and c.jar are both loaded; native runs everything."""
    SIGNATURE = "java.lang.IllegalStateException: duplicate registration"

    def __init__(self, kernel=KERNEL):
        self.kernel, self.launches = kernel, []

    def __call__(self, engine, jars, ticks, timeout, xmx):
        names = {Path(j).name for j in jars}
        self.launches.append((engine, sorted(names)))
        failing = engine == "forbric" and {"a.jar", "c.jar"} <= names
        identity = {"kernel": {"sha256": self.kernel}} if engine == "forbric" else {"launcher": {"sha256": LAUNCHER}}
        return dict(outcome=nc.CRASH if failing else nc.DONE, signature=self.SIGNATURE if failing else None,
                    modSetSha256=nc.mod_set(jars)[1], inputDrift=[], identity=identity, seconds=1.0, secondsToDone=0.5,
                    gametime=[1, 201], exitCode=0, lingeredAfterStop=False, otherThreadFailures=[], crashReports=int(failing),
                    result="/nonexistent/result.json")


def pack(tmp, rows=None, closure=None):
    rows = rows or [("a.jar", "popular"), ("b.jar", "random"), ("c.jar", "random"), ("d.jar", "dep"), ("e.jar", "random")]
    (tmp / "mods").mkdir(parents=True)
    manifest = []
    for name, kind in rows:
        data = name.encode() * 3
        (tmp / "mods" / name).write_bytes(data)
        manifest.append(dict(filename=name, kind=kind, sha1=hashlib.sha1(data).hexdigest(), size=len(data)))
    (tmp / "manifest.json").write_text(json.dumps(manifest))
    (tmp / "closure.json").write_text(json.dumps(closure if closure is not None else {"c.jar": ["d.jar"]}))
    return tmp


class PairVerdict(unittest.TestCase):
    def test_the_four_verdicts(self):
        self.assertEqual(ab.MATCHED_PASS, ab.pair_verdict(result("native", nc.DONE), result("forbric", nc.DONE)))
        self.assertEqual(ab.FORBRIC_ONLY, ab.pair_verdict(result("native", nc.DONE), result("forbric", nc.CRASH)))
        self.assertEqual(ab.NATIVE_ONLY, ab.pair_verdict(result("native", nc.STALL), result("forbric", nc.DONE)))
        self.assertEqual(ab.BOTH_FAIL, ab.pair_verdict(result("native", nc.FAILED_TO_START), result("forbric", nc.CRASH)))

    def test_different_bytes_answer_nothing(self):
        self.assertEqual(ab.INPUT_MISMATCH, ab.pair_verdict(result("native", nc.DONE, digest="1"), result("forbric", nc.CRASH, digest="2")))

    def test_judge(self):
        self.assertEqual(ab.PASS, ab.judge("S", result("forbric", nc.DONE)))
        self.assertEqual(ab.FAIL, ab.judge("S", result("forbric", nc.CRASH, "S")))
        self.assertEqual(ab.UNRESOLVED, ab.judge("S", result("forbric", nc.CRASH, "T")))


class LabRuns(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.data = pack(Path(self.tmp.name))
        self.server = FakeServer()

    def tearDown(self):
        self.tmp.cleanup()

    def lab(self, **kw):
        return ab.Lab(self.data, self.data / "ab", 200, 900, "2G", kernel=KERNEL, launcher=LAUNCHER, launch=kw.get("launch", self.server), verbose=False)

    def test_a_jar_that_is_not_the_manifests_bytes_is_refused(self):
        (self.data / "mods" / "b.jar").write_bytes(b"tampered")
        with self.assertRaises(SystemExit):
            self.lab()

    def test_an_identical_session_runs_once_and_another_kernel_runs_again(self):
        self.lab().run("pack", "forbric", ["a.jar", "b.jar"])
        self.lab().run("per-mod:b.jar", "forbric", ["b.jar", "a.jar"])
        self.assertEqual(1, len(self.server.launches))
        other = ab.Lab(self.data, self.data / "ab", 200, 900, "2G", kernel="m" * 64, launcher=LAUNCHER, launch=FakeServer("m" * 64), verbose=False)
        other.run("pack", "forbric", ["a.jar", "b.jar"])
        self.assertEqual(1, len(other.launch.launches))

    def test_a_kernel_that_changes_under_a_session_is_refused(self):
        with self.assertRaises(SystemExit):
            self.lab(launch=FakeServer("z" * 64)).run("pack", "forbric", ["a.jar"])

    def test_the_minimiser_finds_the_pair_and_native_clears_it(self):
        lab = self.lab()
        outcome = ab.minimise(lab, budget=40)
        self.assertEqual("MINIMISED", outcome["status"])
        self.assertEqual({"a.jar", "c.jar"}, set(outcome["minimal"]))
        self.assertEqual({"a.jar", "c.jar", "d.jar"}, set(outcome["closed"]))
        self.assertEqual(ab.FORBRIC_ONLY, outcome["minimalVerdict"])
        # every Forbric launch carried c.jar's dependency whenever it carried c.jar
        self.assertTrue(all("d.jar" in names for engine, names in self.server.launches if "c.jar" in names))

    def test_a_passing_pack_has_nothing_to_minimise(self):
        lab = ab.Lab(pack(Path(self.tmp.name) / "p", [("a.jar", "popular"), ("b.jar", "random")], {}), Path(self.tmp.name) / "p/ab",
                     200, 900, "2G", kernel=KERNEL, launcher=LAUNCHER, launch=self.server, verbose=False)
        self.assertEqual("PASSED", ab.minimise(lab, budget=5)["status"])

    def test_summary_counts_pairs_and_holds_no_paths(self):
        lab = self.lab()
        ab.per_mod(lab, jobs=2)
        summary = ab.summarise(lab.manifest, lab.records(), KERNEL, LAUNCHER, 200)
        # c.jar alone pulls d.jar, never a.jar: every subject passes on its own.
        self.assertEqual({ab.MATCHED_PASS: 4}, summary["perModCounts"])
        lab.run("pack", "native", [row["filename"] for row in lab.manifest])
        lab.run("pack", "forbric", [row["filename"] for row in lab.manifest])
        summary = ab.summarise(lab.manifest, lab.records(), KERNEL, LAUNCHER, 200)
        self.assertEqual(ab.FORBRIC_ONLY, summary["pack"]["verdict"])
        self.assertNotIn(str(self.data), json.dumps(summary))
        self.assertNotIn("/nonexistent", json.dumps(summary))
        report = Path(self.tmp.name) / "report"
        ab.write_report(report, lab, summary)
        for path in report.iterdir():
            self.assertNotIn(self.tmp.name, path.read_text(), path.name)
            self.assertNotIn("/nonexistent", path.read_text(), path.name)
        self.assertEqual(len(lab.records()), len((report / "sessions.jsonl").read_text().splitlines()))

    def test_narrower_packs_read_each_subjects_own_sessions(self):
        lab = self.lab()
        records = [dict(label="per-mod:" + name, engine=engine, outcome=outcome, identityDigest=KERNEL if engine == "forbric" else LAUNCHER)
                   for name, engine, outcome in [("a.jar", "native", nc.DONE), ("a.jar", "forbric", nc.CRASH),
                                                 ("b.jar", "native", nc.FAILED_TO_START), ("b.jar", "forbric", nc.FAILED_TO_START),
                                                 ("c.jar", "native", nc.DONE), ("c.jar", "forbric", nc.DONE),
                                                 ("e.jar", "native", nc.DONE), ("e.jar", "forbric", nc.DONE)]]
        self.assertEqual(["a.jar", "c.jar", "e.jar"], ab.pack_subjects(lab.manifest, records, KERNEL, LAUNCHER, "native-pass"))
        self.assertEqual(["c.jar", "e.jar"], ab.pack_subjects(lab.manifest, records, KERNEL, LAUNCHER, "matched-pass"))
        # A session of another kernel does not count, so e.jar's Forbric arm is missing and the selection is refused.
        stale = records[:-1] + [dict(records[-1], identityDigest="m" * 64)]
        with self.assertRaises(SystemExit):
            ab.pack_subjects(lab.manifest, stale, KERNEL, LAUNCHER, "matched-pass")

    def test_confirm_reruns_only_pairs_that_disagree(self):
        lab = self.lab()
        ab.per_mod(lab, jobs=1)
        flaky = dict(lab.records()[-1])
        name = flaky["label"].split(":", 1)[1]
        lab.append(dict(flaky, engine="forbric", outcome=nc.STALL, key="stale", identityDigest=KERNEL, label="per-mod:" + name))
        before = len(self.server.launches)
        ab.confirm(lab)
        self.assertEqual(2, len(self.server.launches) - before)
        self.assertEqual(ab.MATCHED_PASS, ab.summarise(lab.manifest, lab.records(), KERNEL, LAUNCHER, 200)["perModNotMatched"].get(name, {}).get("verdict", ab.MATCHED_PASS))


if __name__ == "__main__":
    unittest.main()
