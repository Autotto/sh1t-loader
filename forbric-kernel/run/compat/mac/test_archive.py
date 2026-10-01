import io
import json
import unittest
import zipfile
from archive import jar_ids


def jar(entries):
    data = io.BytesIO()
    with zipfile.ZipFile(data, 'w') as archive:
        for name, value in entries.items():
            archive.writestr(name, value)
    return data.getvalue()


class ArchiveDependenciesTest(unittest.TestCase):
    def test_nested_requirements_are_not_lost(self):
        child = jar({'fabric.mod.json': json.dumps(dict(schemaVersion=1, id='child', version='1', depends={'fabric-api-base': '*'}))})
        host = jar({'fabric.mod.json': json.dumps(dict(schemaVersion=1, id='host', version='1', jars=[dict(file='META-INF/jars/child.jar')])), 'META-INF/jars/child.jar': child})
        provided, required = jar_ids(host)
        self.assertEqual({'host', 'child'}, provided)
        self.assertEqual({'fabric-api-base'}, required)

    def test_jarjar_only_bundle_and_arbitrary_declared_path(self):
        child = jar({'META-INF/neoforge.mods.toml': 'modLoader="javafml"\n[[mods]]\nmodId="child"\nversion="1"\n[[dependencies.child]]\nmodId="api"\ntype="required"\n'})
        host = jar({'META-INF/jarjar/metadata.json': json.dumps(dict(jars=[dict(path='private/payload.jar')])), 'private/payload.jar': child})
        self.assertEqual(({'child'}, {'api'}), jar_ids(host))

    def test_an_internal_provider_satisfies_a_nested_requirement(self):
        child = jar({'fabric.mod.json': json.dumps(dict(schemaVersion=1, id='child', version='1', depends={'host': '*'}))})
        host = jar({'fabric.mod.json': json.dumps(dict(schemaVersion=1, id='host', version='1', jars=[dict(file='child.jar')])), 'child.jar': child})
        self.assertEqual(({'host', 'child'}, set()), jar_ids(host))

    def test_missing_declared_payload_is_a_setup_failure(self):
        host = jar({'META-INF/jarjar/metadata.json': json.dumps(dict(jars=[dict(path='missing.jar')]))})
        with self.assertRaisesRegex(ValueError, 'declared nested jar missing'):
            jar_ids(host)


if __name__ == '__main__':
    unittest.main()
