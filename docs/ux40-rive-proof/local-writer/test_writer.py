import copy, json, math, unittest
from pathlib import Path
from author_examples import miga, tallo
from writer import compile_scene, validate, Binary, uint

class BoundsTests(unittest.TestCase):
    def reject(self, change):
        scene=miga();change(scene)
        with self.assertRaises((ValueError,KeyError,TypeError)): compile_scene(scene)

    def test_originals_deterministic(self):
        for scene in (miga(),tallo()):
            a=compile_scene(scene); self.assertEqual(a,compile_scene(copy.deepcopy(scene)))
            self.assertEqual(a[:4],b'RIVE');self.assertLess(len(a),65536)
        self.assertNotEqual(compile_scene(miga()),compile_scene(tallo()))
    def test_nan(self): self.reject(lambda s:s['nodes'][0].update(y=math.nan))
    def test_infinity(self): self.reject(lambda s:s['nodes'][0].update(y=math.inf))
    def test_bad_coordinate(self): self.reject(lambda s:s['nodes'][0].update(x=3000))
    def test_zero_width(self): self.reject(lambda s:s['nodes'][1].update(width=0))
    def test_oversize_geometry(self): self.reject(lambda s:s['nodes'][1].update(width=1024))
    def test_unknown_geometry(self): self.reject(lambda s:s['nodes'][1].update(kind='script'))
    def test_ellipse_radius(self): self.reject(lambda s:s['nodes'][1].update(radius=8))
    def test_container_type(self): self.reject(lambda s:s.update(nodes='invalid'))
    def test_external_url(self): self.reject(lambda s:s['nodes'][1].update(url='https://example.com/a.png'))
    def test_unknown_scene_key(self): self.reject(lambda s:s.update(script='untrusted()'))
    def test_missing_parent(self): self.reject(lambda s:s['nodes'][1].update(parent='Missing'))
    def test_self_parent(self): self.reject(lambda s:s['nodes'][1].update(parent='EyeL'))
    def test_duplicate_name(self): self.reject(lambda s:s['nodes'][1].update(name='Body'))
    def test_long_name(self): self.reject(lambda s:s['nodes'][1].update(name='a'*81))
    def test_node_budget(self): self.reject(lambda s:s.update(nodes=s['nodes']*20))
    def test_unsupported_property(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(property='script'))
    def test_missing_target(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(node='Missing'))
    def test_missing_reset(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(keys=[[1,132]]))
    def test_unsorted_keys(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(keys=[[0,132],[60,130],[30,132]]))
    def test_out_of_duration(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(keys=[[0,132],[121,132]]))
    def test_duplicate_frame(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(keys=[[0,132],[0,130]]))
    def test_key_budget(self): self.reject(lambda s:s['animations']['Idle']['tracks'][0].update(keys=[[i,132] for i in range(65)]))
    def test_incomplete_state_reset(self): self.reject(lambda s:s['animations']['Reduced']['tracks'].pop())
    def test_reduced_motion_track(self): self.reject(lambda s:s['animations']['Reduced']['tracks'][0].update(keys=[[0,132],[1,140]]))
    def test_reduced_loop(self): self.reject(lambda s:s['animations']['Reduced'].update(loop=True))
    def test_duration_limit(self): self.reject(lambda s:s['animations']['Idle'].update(duration=10000))
    def test_color_bounds(self): self.reject(lambda s:s['nodes'][1].update(color=-1))
    def test_version_gate(self):
        with self.assertRaises(ValueError): Binary().finish(8,0)
    def test_output_budget(self):
        w=Binary();w.data=bytearray(65536)
        with self.assertRaises(ValueError): w.finish()
    def test_primitive_uint(self):
        for value in (-1,2**32,True,3.1):
            with self.assertRaises(ValueError): uint(value)

if __name__=='__main__':
    suite=unittest.defaultTestLoader.loadTestsFromTestCase(BoundsTests)
    result=unittest.TextTestRunner(verbosity=2).run(suite)
    Path('artifacts/writer-validation.json').write_text(json.dumps({'testsRun':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),'passed':result.wasSuccessful(),'scope':'bounded source schema and serializer checks, not security audit or exhaustive fuzzing'},indent=2)+'\n')
    raise SystemExit(not result.wasSuccessful())
