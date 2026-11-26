from pathlib import Path
from collections import Counter
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
QUICKSTART = ROOT / "app/src/main/java/me/rerere/rikkahub/ui/pages/quickstart"


class FileManagerContractsTest(unittest.TestCase):
    def test_web_server_brand_is_updated_for_all_existing_translations(self):
        found = 0
        for path in RES.glob("values*/strings.xml"):
            entry = ET.parse(path).getroot().find("string[@name='setting_page_web_server_desc']")
            if entry is None:
                continue
            found += 1
            text = "".join(entry.itertext())
            self.assertIn("Rikkahub-M", text, str(path))
            self.assertNotIn("RikkaHub", text, str(path))
        self.assertGreaterEqual(found, 8)

    def test_deepseek_import_keeps_the_action_without_the_redundant_title(self):
        page = (QUICKSTART / "QuickStartDeepSeekTab.kt").read_text()
        self.assertNotIn("quick_start_page_deepseek_import_title", page)
        self.assertIn("pickZipLauncher", page)
        self.assertIn("vm::importDeepSeek", page)
        self.assertIn("UiState.Success", page)
        self.assertIn("UiState.Error", page)
        component = (QUICKSTART / "QuickStartComponents.kt").read_text()
        self.assertIn("title: Int? = null", component)
        self.assertIn("headlineContent", component)

    def test_new_file_management_resources_have_matching_localized_keys(self):
        def entries(folder):
            items = list(ET.parse(RES / folder / "file_manager_strings.xml").getroot())
            data = {item.attrib["name"]: "".join(item.itertext()) for item in items}
            self.assertEqual(len(items), len(data), folder)
            return data
        reference = entries("values")
        for locale in ("values-zh", "values-zh-rTW", "values-ar", "values-de", "values-ru", "values-ko-rKR", "values-ja"):
            localized = entries(locale)
            self.assertEqual(set(reference), set(localized), locale)
            for key, text in reference.items():
                self.assertTrue(localized[key].strip(), (locale, key))
                self.assertEqual(Counter(re.findall(r"%[0-9]+\\$[ds]", text)), Counter(re.findall(r"%[0-9]+\\$[ds]", localized[key])), (locale, key))
        self.assertIn("file_manager_owner_unknown", reference)
        self.assertIn("file_manager_export_failed", reference)


if __name__ == "__main__":
    unittest.main()
