#!/usr/bin/env python3
"""Standalone synthetic regressions for the conservative ordinal finalizer.

Run: python3 -m unittest -v test_ordinal_finalizer
Requires only symbolic_dex.py with finalize_ordinal_mappings and the explicit
ordinalMappingRejections report field. No APK/DEX fixtures or runtime injection.
"""
import copy
import unittest
import symbolic_dex as audit


def method(proto='()I', value=15):
    return {'group': 'direct', 'access': 0x1008, 'prototype': proto,
            'body': {'registers': 1, 'ins': 0, 'outs': 0,
                     'instructions': ['const/16 v0, #int %d // #%x' % (value, value), 'return v0'],
                     'catches': []}}


def klass(methods=None):
    return {'access': 1, 'superclass': 'Ljava/lang/Object;', 'interfaces': [], 'fields': {},
            'methods': methods or {},
            'annotations': {'class': [], 'fields': {}, 'methods': {}, 'parameters': {}}}


def inventory(classes):
    return {'format': audit.FORMAT, 'classes': classes, 'completeBinaryMetadata': True}


class OrdinalFinalizerControls(unittest.TestCase):
    def compare(self,old,new):
        return audit.compare(inventory(old),inventory(new))
    def cls(self,value):return klass({'run()I':method(value=value)})
    def names(self,n):return ['LApp$$ExternalSyntheticLambda'+str(i)+';' for i in range(n)]
    def test_colliding_changed_same_name_classes_remain_deltas(self):
        a,b=self.names(2);r=self.compare({a:self.cls(1),b:self.cls(2)},{a:self.cls(3),b:self.cls(1)})
        self.assertFalse(r['equivalent']);self.assertEqual(2,len(r['changedClasses']));self.assertEqual([],r['ordinalMappings']);self.assertEqual(1,len(r['ordinalMappingRejections']))
    def test_collision_prunes_upstream_chain(self):
        a,b,c=self.names(3);r=self.compare({a:self.cls(1),b:self.cls(2),c:self.cls(3)},{a:self.cls(4),b:self.cls(1),c:self.cls(2)})
        self.assertEqual(3,len(r['changedClasses']));self.assertEqual(2,len(r['ordinalMappingRejections']));self.assertEqual([],r['ordinalMappings'])
    def test_closed_permutation_is_preserved(self):
        a,b=self.names(2);r=self.compare({a:self.cls(1),b:self.cls(2)},{a:self.cls(2),b:self.cls(1)})
        self.assertTrue(r['equivalent']);self.assertEqual(2,len(r['ordinalMappings']));self.assertEqual([],r['ordinalMappingRejections'])
    def test_chain_ending_in_free_destination_is_preserved(self):
        a,b,c=self.names(3);r=self.compare({a:self.cls(1),b:self.cls(2)},{b:self.cls(1),c:self.cls(2)})
        self.assertTrue(r['equivalent']);self.assertEqual(2,len(r['ordinalMappings']))
    def test_colliding_changed_same_name_methods_remain_deltas(self):
        a='access$000()I';b='access$100()I';r=self.compare({'LApp;':klass({a:method(value=1),b:method(value=2)})},{'LApp;':klass({a:method(value=3),b:method(value=1)})})
        self.assertEqual(2,len(r['changedClasses'][0]['changedMethods']));self.assertEqual([],r['ordinalMappings']);self.assertEqual(1,len(r['ordinalMappingRejections']))
    def test_method_collision_inside_ordinal_class_is_retained(self):
        owner=self.names(1)[0];a='access$000()I';b='access$100()I';r=self.compare({owner:klass({a:method(value=1),b:method(value=2)})},{owner:klass({a:method(value=3),b:method(value=1)})})
        self.assertEqual(2,len(r['changedClasses'][0]['changedMethods']));self.assertEqual([],r['ordinalMappings']);self.assertEqual(1,len(r['ordinalMappingRejections']))
    def test_method_collision_prunes_upstream_chain(self):
        keys=['access$000()I','access$100()I','access$200()I'];r=self.compare({'LApp;':klass(dict(zip(keys,[method(value=i) for i in (1,2,3)])))},{'LApp;':klass(dict(zip(keys,[method(value=i) for i in (4,1,2)])))})
        self.assertEqual(3,len(r['changedClasses'][0]['changedMethods']));self.assertEqual(2,len(r['ordinalMappingRejections']))
    def test_prototype_only_collision_fails_closed(self):
        a,b=self.names(2);methods={'call('+a+')I':method(proto='('+a+')I'),'call('+b+')I':method(proto='('+b+')I')}
        old={a:self.cls(1),'LApp;':klass(methods)};new={b:self.cls(1),'LApp;':klass(copy.deepcopy(methods))}
        with self.assertRaisesRegex(ValueError,'Identity-only method collision'):self.compare(old,new)
    def test_rejected_class_move_revalidates_method_owner(self):
        a,b=self.names(2);old_key='access$000()I';new_key='access$100()I';body=method(value=1)
        old={a:klass({old_key:body}),b:self.cls(2)};new={a:klass(),b:klass({new_key:copy.deepcopy(body)})}
        proofs=[{'kind':'class','before':a,'after':b,'exactSelfNormalizedClassSha256':'direct finalizer control'},
                {'kind':'method','owner':a,'before':old_key,'after':new_key,'exactBodyPrototypeAccessSha256':audit.digest(body)}]
        mapped,kept,rejected=audit.finalize_ordinal_mappings(old,new,{a:b},{(a,old_key):new_key},proofs)
        self.assertEqual([],kept);self.assertEqual(2,len(rejected));self.assertEqual(set(old),set(mapped));self.assertEqual({old_key},set(mapped[a]['methods']))
    def test_unrelated_valid_mapping_survives_collision(self):
        a,b,c,d=self.names(4);r=self.compare({a:self.cls(1),b:self.cls(2),c:self.cls(3)},{a:self.cls(4),b:self.cls(1),d:self.cls(3)})
        self.assertFalse(r['equivalent']);self.assertEqual(2,len(r['changedClasses']));self.assertEqual([(c,d)],[(p['before'],p['after']) for p in r['ordinalMappings']])
    def test_referenced_class_proof_invalidated_is_pruned(self):
        a,b,c,d=self.names(4)
        source=self.cls(3);source['fields']={'ref':{'type':a}}
        target=audit.translate(source,{a:b},{})
        old={a:self.cls(1),b:self.cls(2),c:source};new={a:self.cls(4),b:self.cls(1),d:target}
        r=self.compare(old,new)
        self.assertEqual([],r['ordinalMappings']);self.assertEqual(2,len(r['ordinalMappingRejections']));self.assertIn(c,r['removedClasses']);self.assertIn(d,r['addedClasses'])
    def test_referenced_method_proof_invalidated_is_pruned(self):
        a,b=self.names(2);old_method=method();old_method['body']['instructions']=['new-instance v0, '+a,'return v0'];new_method=audit.translate(old_method,{a:b},{})
        old={a:self.cls(1),b:self.cls(2),'LApp;':klass({'access$000()I':old_method})};new={a:self.cls(3),b:self.cls(1),'LApp;':klass({'access$100()I':new_method})}
        r=self.compare(old,new);self.assertEqual([],r['ordinalMappings']);self.assertEqual(2,len(r['ordinalMappingRejections']))


if __name__ == '__main__':
    unittest.main(verbosity=2)
