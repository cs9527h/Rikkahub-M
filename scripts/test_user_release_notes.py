import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import user_release_notes as notes


class UserReleaseNotesTest(unittest.TestCase):
    def render(self, english=None):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            updates = root / "updates.json"
            updates.write_text(json.dumps({"releases": [{
                "version": "1.2.3", "summary": "修复聊天", "body": "## 修复\n\n- 保留草稿\n"
            }]}), encoding="utf-8")
            if english is not None:
                directory = root / "docs" / "releases"
                directory.mkdir(parents=True)
                (directory / "1.2.3.en.md").write_text(english, encoding="utf-8")
            output = io.StringIO()
            with contextlib.chdir(root), patch.object(notes, "UPDATES", updates), \
                    patch("sys.argv", ["notes", "1.2.3"]), contextlib.redirect_stdout(output):
                code = notes.main()
            return code, output.getvalue()

    def test_existing_versions_without_english_keep_chinese_format(self):
        code, text = self.render()
        self.assertEqual(0, code)
        self.assertIn("### 修复\n\n- 保留草稿", text)
        self.assertNotIn("Updates:", text)
        self.assertIn("可直接覆盖安装", text)

    def test_optional_english_is_included_without_new_category_headings(self):
        code, text = self.render("Updates:\n\n- Keep drafts.\n")
        self.assertEqual(0, code)
        self.assertIn("Updates:\n\n- Keep drafts.", text)
        self.assertEqual(["### 修复"], [line for line in text.splitlines() if line.startswith("### ")])
        self.assertLess(text.index("保留草稿"), text.index("Keep drafts."))


if __name__ == "__main__":
    unittest.main()
