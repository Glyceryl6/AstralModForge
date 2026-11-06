// 每个 MOD 拥有独立的内嵌词典和可编辑 JSON。
// Each MOD owns embedded defaults + editable overrides under BepInEx/config/<id>/lang.
using System;
using System.IO;
using System.Reflection;
using System.Collections.Generic;
using System.Text.Json;
using BepInEx;
using BepInEx.Configuration;
using BepInEx.Logging;
namespace AstralModForge.Generated.dev_example_hello {
    internal static class ModLocalization {
        private const string Id = "dev.example.hello";
        private static ConfigEntry<string>? _selected;
        private static ManualLogSource? _log;
        private static readonly Dictionary<string, Dictionary<string,string>> Cache = new();
        private static readonly Dictionary<string,DateTime> Stamps = new();
        private static float _next;
        internal static void Initialize(ConfigFile config, ManualLogSource log) {
            _log = log;
            _selected = config.Bind("Language", "Locale", "zh_cn", "zh_cn / en_us");
            Reload("zh_cn"); Reload("en_us");
        }
        public static string T(string key) => Translate(key);
        public static string Translate(string key) {
            if (string.IsNullOrEmpty(key)) return key;
            string lang = _selected?.Value == "en_us" ? "en_us" : "zh_cn";
            if (Cache.TryGetValue(lang, out var data) && data.TryGetValue(key, out string? value)) return value;
            if (Cache.TryGetValue("zh_cn", out var fallback) && fallback.TryGetValue(key, out string? zh)) return zh;
            return key;
        }
        internal static void Poll() {
            if (UnityEngine.Time.realtimeSinceStartup < _next) return;
            _next = UnityEngine.Time.realtimeSinceStartup + 3f;
            Reload("zh_cn"); Reload("en_us");
        }
        private static void Reload(string lang) {
            try {
                string dir = Path.Combine(Paths.ConfigPath, Id, "lang");
                Directory.CreateDirectory(dir);
                string file = Path.Combine(dir, lang + ".json");
                DateTime stamp = File.Exists(file) ? File.GetLastWriteTimeUtc(file) : DateTime.MinValue;
                if (Stamps.TryGetValue(lang, out DateTime old) && old == stamp) return;
                var embedded = new Dictionary<string,string>();
                using (Stream? stream = Assembly.GetExecutingAssembly().GetManifestResourceStream(Id + ".lang." + lang + ".json")) {
                    if (stream != null) embedded = JsonSerializer.Deserialize<Dictionary<string,string>>(stream) ?? new();
                }
                if (!File.Exists(file)) { File.WriteAllText(file, JsonSerializer.Serialize(embedded, new JsonSerializerOptions { WriteIndented = true }));
                    stamp = File.GetLastWriteTimeUtc(file); }
                string edited = File.ReadAllText(file);
                var overrides = JsonSerializer.Deserialize<Dictionary<string,string>>(edited) ?? new();
                bool filledMissing = false;
                foreach (var item in embedded) if (!overrides.ContainsKey(item.Key)) { overrides[item.Key] = item.Value; filledMissing = true; }
                if (filledMissing) { File.WriteAllText(file, JsonSerializer.Serialize(overrides, new JsonSerializerOptions { WriteIndented = true }));
                    stamp = File.GetLastWriteTimeUtc(file); }
                foreach (var item in overrides) embedded[item.Key] = item.Value;
                Cache[lang] = embedded; Stamps[lang] = stamp;
            } catch(Exception ex) { _log?.LogWarning("Language reload skipped: " + ex.Message); }
        }
    }
}
