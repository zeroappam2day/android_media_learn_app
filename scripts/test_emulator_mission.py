import unittest
from emulator_mission import classify_runtime, installed_base_path


class RuntimeBoundaryTest(unittest.TestCase):
    def test_actual_emulator_identity_is_accepted(self):
        result = classify_runtime("emulator-5554", "emulator", {
            "ro.hardware": "ranchu", "ro.kernel.qemu": "1", "ro.build.version.sdk": "36"})
        self.assertEqual(36, result["api"])

    def test_phone_cannot_be_relabelled_as_emulator(self):
        for serial, props in (("R5CW904CSWJ", {"ro.hardware": "ranchu", "ro.kernel.qemu": "1"}),
                              ("emulator-5554", {"ro.hardware": "qcom", "ro.kernel.qemu": "0"})):
            with self.assertRaises(ValueError):
                classify_runtime(serial, "emulator", props)

    def test_waydroid_needs_live_host_and_device_agreement(self):
        props = {"ro.hardware": "waydroid", "ro.product.device": "waydroid_x86_64",
                 "ro.build.version.sdk": "36"}
        self.assertEqual("waydroid", classify_runtime("192.168.240.112:5555", "waydroid", props,
                                                       "192.168.240.112")["runtime"])
        for ip in (None, "192.168.240.111"):
            with self.assertRaises(ValueError):
                classify_runtime("192.168.240.112:5555", "waydroid", props, ip)

    def test_tcp_phone_is_rejected(self):
        with self.assertRaises(ValueError):
            classify_runtime("192.168.240.112:5555", "waydroid", {"ro.hardware": "qcom"}, "192.168.240.112")

    def test_adb_option_injection_is_rejected(self):
        with self.assertRaises(ValueError):
            classify_runtime("-Hhost", "emulator", {})

    def test_modern_android_apk_path_from_observed_device_is_accepted(self):
        path = "/data/app/~~nhA_NpH4xjhawlN4MU_dCw==/com.example.preview-LYKwVtk1-Q0wLjl65rj5oQ==/base.apk"
        self.assertEqual(path, installed_base_path("package:" + path + "\n"))

    def test_multiple_installed_apk_paths_are_not_mistaken_for_one_tested_artifact(self):
        with self.assertRaises(RuntimeError):
            installed_base_path("package:/data/app/one/base.apk\npackage:/data/app/two/base.apk\n")

    def test_installed_path_shell_metacharacters_are_rejected(self):
        with self.assertRaises(RuntimeError):
            installed_base_path("package:/data/app/x;echo-secret/base.apk\n")


if __name__ == "__main__":
    unittest.main()
