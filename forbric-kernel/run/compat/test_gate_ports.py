"""Every concurrent gate reads a port the scheduler actually hands it.

gates-parallel.py promises "Every concurrent gate therefore gets its own port block, not a shared default" and
names the hazard that promise exists for: two gates on one port, the loser prints "FAILED TO BIND TO PORT" and
then still prints "Stopping server", so the clean-shutdown assertion passes and the gate reads GREEN while its
server never existed (lib.sh documents the same failure).

The scheduler keeps that promise by exporting its whole block into PORT_VARS. A gate that reads a knob OUTSIDE
that tuple gets nothing, silently falls back to its own literal, and on the slot where the two meet is exactly
the false GREEN above. PORT_VARS is hand-maintained, so the gate that drifts is the one nobody notices: this
walks the checked-in gates and fails on the drift instead of measuring it in a run that cannot be reproduced.

The check reads the text rather than running a gate: a gate is read here the way the scheduler reads it (its
GATE-PARALLEL declaration plus its port knob), which is also what keeps it honest -- DECL below is the same
shape as gates-parallel.py's, so a gate that stops declaring itself stops being covered here too.
"""
import importlib.util
import re
import unittest
from pathlib import Path

COMPAT = Path(__file__).resolve().parent
RUN = COMPAT.parent

_spec = importlib.util.spec_from_file_location('gates_parallel', COMPAT / 'gates-parallel.py')
scheduler = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(scheduler)

# The declaration gates-all.sh documents, matched the way the scheduler matches it.
DECLARED = re.compile(r'^#\s*GATE-PARALLEL:\s*(.*)$', re.M)
# `set -u`, so an unset knob is never read bare: the spelling a gate uses is always one of these two.
PORT_KNOB = re.compile(r'\$\{([A-Za-z0-9_]+_PORT)\b|\$([A-Za-z0-9_]+_PORT)\b')


def parallel_gates(run_dir=None):
    """The gates that declared themselves runnable next to another one, and the port knobs they read."""
    found = {}
    for path in sorted((run_dir if run_dir is not None else RUN).glob('gate-m*.sh')):
        source = path.read_text(encoding='utf-8', errors='replace')
        if not DECLARED.search(source):
            continue
        found[path.name] = sorted({one or two for one, two in PORT_KNOB.findall(source)})
    return found


class GatePortTest(unittest.TestCase):
    def test_every_parallel_gate_reads_a_port_the_scheduler_exports(self):
        # The scheduler writes every name below into the gate's environment, so no port block means no port.
        unmanaged = {name: [v for v in knobs if v not in scheduler.PORT_VARS]
                     for name, knobs in parallel_gates().items()}
        unmanaged = {name: knobs for name, knobs in unmanaged.items() if knobs}
        self.assertEqual({}, unmanaged, 'a gate that looks for a port the scheduler never exports keeps its own '
                         'literal, and the slot where that literal meets an exported one is two servers on one '
                         'port: the loser still prints "Stopping server", so both read GREEN. Add the knob to '
                         'PORT_VARS in gates-parallel.py, or have the gate read GATE_PORT like the rest')

    def test_a_parallel_gate_with_no_port_knob_is_not_what_this_checks(self):
        # A gate that binds nothing needs no block, and one that grabs its port from the OS (port 0) names no
        # knob either. Only a gate that reads a *_PORT it was never given is the defect above.
        self.assertNotIn('GATE_PORT', parallel_gates())

    def test_a_gate_that_reads_an_unexported_knob_is_caught(self):
        # The control: without it, a walk that finds nothing would pass the test above and prove nothing.
        with self._scratch() as run:
            (run / 'gate-m99-probe.sh').write_text(
                '#!/usr/bin/env bash\n# GATE-PARALLEL: rundirs=probe mem=10\n'
                'PORT="${M99_PORT:-25999}"\nGATE_PORT="${GATE_PORT:-25565}"\n', encoding='utf-8')
            self.assertEqual({'gate-m99-probe.sh': ['GATE_PORT', 'M99_PORT']}, parallel_gates(run))

    def test_the_walk_sees_the_checked_in_gates(self):
        # And it is looking at all of them: a wrong directory would find no parallel gate and pass silently.
        gates = parallel_gates()
        self.assertGreater(len(gates), 20, f'only {len(gates)} parallel gates found under {RUN}')

    class _scratch:
        def __enter__(self):
            import tempfile
            self._temporary = tempfile.TemporaryDirectory(prefix='gate-ports-')
            return Path(self._temporary.name)

        def __exit__(self, *exception):
            self._temporary.cleanup()
            return False


if __name__ == '__main__':
    unittest.main()
