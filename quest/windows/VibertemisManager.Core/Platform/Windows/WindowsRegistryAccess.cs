// Windows registry access via Microsoft.Win32.Registry.
//
// POSIX build excludes this file. We never shell-parse reg.exe /
// wmic. The accessor is read-only in the production manager; the
// installer writes its HKCU Run entry through the Inno Setup
// Registry section, not through this code.
#if WINDOWS
using System.Collections.Generic;
using Microsoft.Win32;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsRegistryAccess : IRegistryAccess
{
    public string? TryGetString(string hive, string subKey, string valueName)
    {
        try
        {
            using var root = OpenHive(hive);
            if (root is null) return null;
            using var key = root.OpenSubKey(subKey, writable: false);
            if (key is null) return null;
            var v = key.GetValue(valueName);
            return v as string;
        }
        catch
        {
            return null;
        }
    }

    public int? TryGetDword(string hive, string subKey, string valueName)
    {
        try
        {
            using var root = OpenHive(hive);
            if (root is null) return null;
            using var key = root.OpenSubKey(subKey, writable: false);
            if (key is null) return null;
            var v = key.GetValue(valueName);
            return v switch
            {
                null => null,
                int i => i,
                uint u when u <= int.MaxValue => (int)u,
                long l when l is >= int.MinValue and <= int.MaxValue => (int)l,
                string s when int.TryParse(s, System.Globalization.NumberStyles.Integer,
                    System.Globalization.CultureInfo.InvariantCulture, out var parsed) => parsed,
                _ => null,
            };
        }
        catch
        {
            return null;
        }
    }

    public IEnumerable<(string ValueName, string Data)> EnumerateValues(string hive, string subKey)
    {
        var empty = System.Array.Empty<(string, string)>();
        try
        {
            using var root = OpenHive(hive);
            if (root is null) return empty;
            using var key = root.OpenSubKey(subKey, writable: false);
            if (key is null) return empty;
            var list = new List<(string, string)>();
            foreach (var name in key.GetValueNames())
            {
                var data = key.GetValue(name);
                if (data is string s) list.Add((name, s));
            }
            return list;
        }
        catch
        {
            return empty;
        }
    }

    private static RegistryKey? OpenHive(string hive) => hive switch
    {
        "HKLM" => Registry.LocalMachine,
        "HKCU" => Registry.CurrentUser,
        "HKCR" => Registry.ClassesRoot,
        "HKU" => Registry.Users,
        _ => null,
    };
}
#endif