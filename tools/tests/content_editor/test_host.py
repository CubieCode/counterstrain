"""The host composite must match host_overlay.fsh and HostOverlayLayer (see contentedit/host.py)."""
import unittest

import fixture
from contentedit import host, png

GREY = (100, 100, 100, 255)


def img(width, height, fn):
    return png.Image(width, height, [[fn(x, y) for x in range(width)] for y in range(height)])


class CompositeTest(unittest.TestCase):
    def test_pattern_repeats_one_texel_per_mob_texel(self):
        mob = img(64, 32, lambda x, y: GREY)
        pattern = img(32, 32, lambda x, y: (x * 8, y * 8, 0, 255))
        out = host.composite(mob, pattern)
        self.assertEqual(out.pixel(3, 5), (24, 40, 0, 255))
        self.assertEqual(out.pixel(35, 5), out.pixel(3, 5))  # wraps at the pattern's width
        self.assertEqual((out.width, out.height), (64, 32))

    def test_mob_alpha_below_a_tenth_is_left_alone(self):
        pattern = img(4, 4, lambda x, y: (255, 0, 0, 255))
        out = host.composite(img(2, 1, lambda x, y: (100, 100, 100, (25, 26)[x])), pattern)
        self.assertEqual(out.pixel(0, 0), (100, 100, 100, 25))  # 25/255 < 0.1: discarded
        self.assertEqual(out.pixel(1, 0), (255, 0, 0, 26))      # 26/255 >= 0.1: drawn, mob alpha kept

    def test_intensity_scales_pattern_alpha(self):
        pattern = img(1, 1, lambda x, y: (200, 0, 0, 255))
        self.assertEqual(host.composite(img(1, 1, lambda x, y: GREY), pattern, intensity=0.5).pixel(0, 0),
                         (150, 50, 50, 255))
        self.assertEqual(host.composite(img(1, 1, lambda x, y: GREY), pattern, intensity=0).pixel(0, 0), GREY)

    def test_nearly_transparent_pattern_is_discarded(self):
        pattern = img(1, 1, lambda x, y: (255, 255, 255, 1))  # 1/255 < 0.004
        self.assertEqual(host.composite(img(1, 1, lambda x, y: GREY), pattern).pixel(0, 0), GREY)

    def test_glow_goes_on_top(self):
        pattern = img(1, 1, lambda x, y: (200, 0, 200, 255))
        glow = img(2, 1, lambda x, y: ((0, 255, 0, 255), (0, 0, 0, 0))[x])
        out = host.composite(img(2, 1, lambda x, y: GREY), pattern, glow)
        self.assertEqual(out.pixel(0, 0), (0, 255, 0, 255))
        self.assertEqual(out.pixel(1, 0), (200, 0, 200, 255))

    def test_stage_patterns_follow_the_layer(self):
        self.assertEqual([host.stage_pattern(s) for s in (1, 2, 3, 4, 7)],
                         ["stage_1.png", "stage_2.png", "stage_3.png", "stage_3.png", "stage_3.png"])
        self.assertTrue(host.is_host_pattern(fixture.HOST + "veins_glow.png"))
        self.assertFalse(host.is_host_pattern(fixture.TEX + "block/stone.png"))

    def test_mannequins_use_vanilla_layouts(self):
        humanoid, quadruped = host.mannequin(), host.mannequin("quadruped")
        self.assertEqual((humanoid.width, humanoid.height, quadruped.width, quadruped.height), (64, 64, 64, 32))
        self.assertEqual(humanoid.pixel(9, 9)[3], 255)   # head front
        self.assertEqual(humanoid.pixel(0, 0)[3], 0)     # unused corner stays transparent


if __name__ == "__main__":
    unittest.main()
