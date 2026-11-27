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

    def test_deepseek_import_description_uses_current_assistant_without_a_name(self):
        current_assistant = {
            "values": "current assistant",
            "values-zh": "当前助手",
            "values-zh-rTW": "目前的助理",
            "values-ja": "現在のアシスタント",
            "values-ko-rKR": "현재 어시스턴트",
            "values-ru": "текущего ассистента",
            "values-de": "aktuellen Assistenten",
            "values-ar": "المساعد الحالي",
        }
        for locale, assistant in current_assistant.items():
            with self.subTest(locale=locale):
                entry = ET.parse(RES / locale / "strings.xml").getroot().find(
                    "string[@name='quick_start_page_deepseek_import_desc']"
                )
                self.assertIsNotNone(entry)
                text = "".join(entry.itertext())
                self.assertIn(assistant, text)
                self.assertIn("ZIP", text)
                self.assertNotIn("%", text)
                self.assertNotIn("李鱼", text)
        page = (QUICKSTART / "QuickStartDeepSeekTab.kt").read_text()
        self.assertNotIn("descriptionArgs", page)
        self.assertNotIn("assistantName", page)
        self.assertNotIn("getCurrentAssistant", page)
        self.assertNotIn("assistant_page_default_assistant", page)
        self.assertIn("uri?.let(vm::importDeepSeek)", page)
        self.assertIn('pickZipLauncher.launch(arrayOf("application/zip"))', page)

    def test_simplified_chinese_import_description_has_only_commas_and_final_period(self):
        entry = ET.parse(RES / "values-zh/strings.xml").getroot().find(
            "string[@name='quick_start_page_deepseek_import_desc']"
        )
        text = "".join(entry.itertext())
        self.assertEqual(
            text,
            "选择刚才的 ZIP，每个会话导入当前显示的那条分支，包括思考过程和附件，"
            "导入的聊天会放进当前助手，已导入的会话会自动跳过，可以放心重复导入。",
        )
        self.assertEqual(re.findall(r"[^\w\s]", text), ["，"] * 5 + ["。"])

    def test_speech_description_configures_services_not_providers(self):
        service_terms = {
            "values": "services",
            "values-zh": "服务",
            "values-zh-rTW": "服務",
            "values-ja": "サービス",
            "values-ko-rKR": "서비스",
            "values-ru": "служб",
            "values-de": "dienste",
            "values-ar": "خدمات",
        }
        for locale, service in service_terms.items():
            with self.subTest(locale=locale):
                entry = ET.parse(RES / locale / "strings.xml").getroot().find(
                    "string[@name='setting_page_tts_service_desc']"
                )
                self.assertIsNotNone(entry)
                text = "".join(entry.itertext())
                self.assertIn(service, text.casefold())
                self.assertNotRegex(
                    text.casefold(),
                    r"provider|提供商|供應商|供应商|プロバイダ|프로바이더|공급자|"
                    r"провайдер|anbieter|مزود|مزوّد|موفر",
                )
                if locale == "values-zh":
                    self.assertEqual(text, "配置语音合成与语音识别服务")

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
