import unittest
from api_surface import removed_signatures

class CompatibilityRulesTest(unittest.TestCase):
    def test_opening_class_preserves_existing_constructor(self):
        old={'modules':{'sdk':['public final class example.Type','public final class example.Type :: public example.Type(int);']}}
        new={'modules':{'sdk':['public class example.Type','public class example.Type :: public example.Type(int);']}}
        self.assertEqual({},removed_signatures(old,new))
    def test_closing_extensible_class_is_rejected(self):
        self.assertIn('sdk',removed_signatures({'modules':{'sdk':['public class example.Type']}},
            {'modules':{'sdk':['public final class example.Type']}}))
    def test_parameter_removal_is_still_rejected(self):
        old={'modules':{'sdk':['public final class example.Type :: public example.Type(int);']}}
        new={'modules':{'sdk':['public class example.Type :: public example.Type();']}}
        self.assertIn('sdk',removed_signatures(old,new))
    def test_compiler_enum_switch_holder_is_not_a_public_contract(self):
        old={'modules':{'sdk':['public final class example.Type$WhenMappings :: public static final int[] $EnumSwitchMapping$0;']}}
        self.assertEqual({},removed_signatures(old,{'modules':{'sdk':[]}}))

if __name__=='__main__':unittest.main()
