// 删除此示例后，请同步修改 Plugin.Load 的调用。
// Remove the sample and update Plugin.Load when starting real features.
namespace AstralModForge.Generated.dev_example_hello.Features {
    internal static class SampleFeature {
        internal static void Initialize() {
            Plugin.ModLog.LogInfo(ModLocalization.T("dev.example.hello.sample.loaded"));
        }
    }
}
