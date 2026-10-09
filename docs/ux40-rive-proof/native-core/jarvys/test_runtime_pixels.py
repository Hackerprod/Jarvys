#!/usr/bin/env python3
"""Full positive fixture and fail-closed negative tests for runtime image proof.

Set JARVYS_PROOF_OUTPUT to an existing complete rendered fixture. Mutations use
private temporary copies and never change the original images, scene or report.
"""
import copy
import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
import sys
sys.dont_write_bytecode = True
from PIL import Image
import check_runtime_pixels as checker


class RuntimePixelHarnessTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not os.environ.get('JARVYS_PROOF_OUTPUT'):
            raise RuntimeError('Set JARVYS_PROOF_OUTPUT to a complete real runtime fixture; tests do not skip missing evidence')
        cls.root = Path(os.environ['JARVYS_PROOF_OUTPUT']).resolve()
        cls.source = Path(os.environ.get('JARVYS_SCENE_SOURCE', str(Path(__file__).with_name('jarvys-main.scene.json'))))
        cls.report = json.loads((cls.root / 'runtime-render-report.json').read_text())

    def write_report(self, root, report=None):
        (root / 'runtime-render-report.json').write_text(json.dumps(self.report if report is None else report))

    def copy_fixture(self, root):
        self.write_report(root)
        shutil.copytree(self.root / 'runtime-frames', root / 'runtime-frames')

    def test_complete_current_fixture_passes(self):
        result = checker.validate_fixture(self.root, self.source)
        self.assertTrue(result['passed'])
        self.assertEqual(tuple(result['results']), checker.MODES)
        for state in result['results'].values():
            self.assertTrue(state['reduced_static_after_4s'])
            self.assertTrue(state.get('one_shot_holds_final_after_3s', True))

    def test_empty_report_is_rejected(self):
        for report, message in (({}, 'frozen source hash'), (dict(self.report, states=[]), 'exactly nine')):
            with self.subTest(report=report), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); self.write_report(root, report)
                with self.assertRaisesRegex(ValueError, message):
                    checker.validate_fixture(root, self.source)

    def test_missing_state_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); report = copy.deepcopy(self.report); report['states'].pop()
            self.write_report(root, report)
            with self.assertRaisesRegex(ValueError, 'exactly nine'):
                checker.validate_fixture(root, self.source)

    def test_invalid_state_metadata_is_rejected(self):
        changes = (
            ('missing source hash', lambda r: r.pop('sourceSha256'), 'frozen source hash'),
            ('wrong source hash', lambda r: r.update(sourceSha256='0'*64), 'frozen source hash'),
            ('missing binary hash', lambda r: r.pop('rivSha256'), 'frozen binary hash'),
            ('wrong binary hash', lambda r: r.update(rivSha256='0'*64), 'frozen binary hash'),
            ('duplicate name', lambda r: r['states'][1].update(name='Idle'), 'unique state names'),
            ('duplicate mode', lambda r: r['states'][1].update(mode=0), 'unique mode indices'),
            ('wrong sizes', lambda r: r.update(sizes=[22, 30, 34]), 'four render sizes'),
            ('duplicate size', lambda r: r.update(sizes=[22, 30, 34, 34]), 'four render sizes'),
            ('zero frames', lambda r: r['states'][0].update(renderFrames=0), 'positive frame count'),
            ('wrong frames', lambda r: r['states'][0].update(renderFrames=99), 'positive frame count'),
            ('wrong fps', lambda r: r['states'][0].update(fps=15), 'Wrong fps'),
            ('wrong duration', lambda r: r['states'][0].update(durationSeconds=4), 'Wrong duration'),
            ('wrong loop', lambda r: r['states'][0].update(loop=False), 'Wrong loop'),
            ('wrong total', lambda r: r.update(captures=0), 'capture count'),
        )
        for label, change, message in changes:
            with self.subTest(case=label), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); report = copy.deepcopy(self.report); change(report)
                self.write_report(root, report)
                with self.assertRaisesRegex(ValueError, message):
                    checker.validate_fixture(root, self.source)

    def test_empty_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaisesRegex(ValueError, 'Missing runtime render report'):
                checker.validate_fixture(Path(directory), self.source)

    def test_empty_frame_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.write_report(root)
            (root / 'runtime-frames').mkdir()
            with self.assertRaisesRegex(ValueError, 'Missing or extra state directories'):
                checker.validate_fixture(root, self.source)

    def test_missing_frame_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            (root / 'runtime-frames' / 'Idle' / '22-000.png').unlink()
            with self.assertRaisesRegex(ValueError, 'Missing or extra frame files for Idle'):
                checker.validate_fixture(root, self.source)

    def test_empty_state_frame_glob_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            folder = root / 'runtime-frames' / 'Idle'
            for image in folder.glob('22-[0-9]*.png'): image.unlink()
            with self.assertRaisesRegex(ValueError, 'Missing or extra frame files for Idle'):
                checker.validate_fixture(root, self.source)

    def test_extra_frame_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            folder = root / 'runtime-frames' / 'Idle'
            shutil.copyfile(folder / '22-000.png', folder / '22-999.png')
            with self.assertRaisesRegex(ValueError, 'Missing or extra frame files for Idle'):
                checker.validate_fixture(root, self.source)

    def test_wrong_image_dimensions_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            Image.new('RGBA', (21, 22), 'white').save(root / 'runtime-frames' / 'Idle' / '22-000.png')
            with self.assertRaisesRegex(ValueError, 'Wrong image dimensions'):
                checker.validate_fixture(root, self.source)

    def test_transparent_empty_frame_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            Image.new('RGBA', (22, 22), (0, 0, 0, 0)).save(root / 'runtime-frames' / 'Idle' / '22-000.png')
            with self.assertRaisesRegex(ValueError, 'Empty transparent image'):
                checker.validate_fixture(root, self.source)


    def test_changed_reduced_pose_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            path = root / 'runtime-frames' / 'Idle' / '256-reduced-after-4s.png'
            with Image.open(path) as original: image = original.copy()
            r, g, b, a = image.getpixel((128, 128))
            image.putpixel((128, 128), (255-r, 255-g, 255-b, a)); image.save(path)
            with self.assertRaisesRegex(ValueError, 'Reduced pose changed: Idle'):
                checker.validate_fixture(root, self.source)

    def test_changed_terminal_pose_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); self.copy_fixture(root)
            path = root / 'runtime-frames' / 'Done' / '22-after-3s.png'
            with Image.open(path) as original: image = original.copy()
            r, g, b, a = image.getpixel((11, 11))
            image.putpixel((11, 11), (255-r, 255-g, 255-b, a)); image.save(path)
            with self.assertRaisesRegex(ValueError, 'Terminal pose changed after settled endpoint: Done 22'):
                checker.validate_fixture(root, self.source)


if __name__ == '__main__':
    unittest.main()
