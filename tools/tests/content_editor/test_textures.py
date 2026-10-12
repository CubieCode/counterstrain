import unittest

import fixture
from contentedit import textures

ORIGINAL = fixture.solid(16, 16, marker=True)
MCMETA = '{"animation": {"frametime": 4}}'


def states(checks):
    return {c["id"]: c["state"] for c in checks}


class TextureChecksTest(unittest.TestCase):
    def test_same_size_unmarked_draft_passes(self):
        checks, info = textures.validate(fixture.solid(16, 16), "draft", original=ORIGINAL)
        self.assertEqual(states(checks), {"png": "pass", "mcmeta": "pass", "size": "pass", "marker": "pass"})
        self.assertEqual((info["width"], info["height"]), (16, 16))

    def test_power_of_two_scale_passes_and_other_sizes_fail(self):
        self.assertEqual(states(textures.validate(fixture.solid(64, 64), "draft", original=ORIGINAL)[0])["size"], "pass")
        self.assertEqual(states(textures.validate(fixture.solid(8, 8), "draft", original=ORIGINAL)[0])["size"], "pass")
        self.assertEqual(states(textures.validate(fixture.solid(24, 24), "draft", original=ORIGINAL)[0])["size"], "fail")
        self.assertEqual(states(textures.validate(fixture.solid(32, 16), "draft", original=ORIGINAL)[0])["size"], "fail")

    def test_marker_must_match_status(self):
        marked, plain = fixture.solid(16, 16, marker=True), fixture.solid(16, 16)
        self.assertEqual(states(textures.validate(marked, "placeholder", original=ORIGINAL)[0])["marker"], "pass")
        self.assertEqual(states(textures.validate(plain, "placeholder", original=ORIGINAL)[0])["marker"], "fail")
        self.assertEqual(states(textures.validate(marked, "draft", original=ORIGINAL)[0])["marker"], "fail")
        self.assertEqual(states(textures.validate(marked, "final", original=ORIGINAL)[0])["marker"], "fail")
        # 32 px wide textures carry a 4×4 marker, as tools/check-provenance.py requires.
        big = fixture.solid(32, 32, marker=True)
        self.assertEqual(states(textures.validate(big, "placeholder")[0])["marker"], "pass")

    def test_gui_images_use_a_label_not_the_marker(self):
        checks = states(textures.validate(fixture.solid(16, 16), "placeholder", marker_rule=False)[0])
        self.assertEqual(checks["marker"], "info")

    def test_animation_strip_needs_mcmeta(self):
        strip = fixture.solid(16, 64)
        without = states(textures.validate(strip, "draft", original=ORIGINAL)[0])
        self.assertEqual(without["mcmeta"], "fail")
        with_meta = states(textures.validate(strip, "draft", original=ORIGINAL, mcmeta=MCMETA)[0])
        self.assertEqual((with_meta["mcmeta"], with_meta["size"]), ("pass", "pass"))
        broken = states(textures.validate(strip, "draft", original=ORIGINAL, mcmeta="{}")[0])
        self.assertEqual(broken["mcmeta"], "fail")

    def test_not_a_png_and_oversized_uploads_fail(self):
        self.assertEqual(states(textures.validate(b"GIF89a....", "draft")[0]), {"png": "fail"})
        self.assertEqual(states(textures.validate(b"\x89PNG\r\n\x1a\n" + b"0" * textures.MAX_BYTES, "draft")[0]),
                         {"png": "fail"})

    def test_power_of_two(self):
        self.assertTrue(all(textures.power_of_two(r) for r in (1, 2, 4, 0.5, 0.25)))
        self.assertFalse(any(textures.power_of_two(r) for r in (3, 1.5, 0, 6)))


if __name__ == "__main__":
    unittest.main()
