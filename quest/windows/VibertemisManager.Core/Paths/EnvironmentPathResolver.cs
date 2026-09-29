using System;
using System.IO;
using VibertemisManager.Core.Platform.Abstractions;
namespace VibertemisManager.Core.Paths;
public sealed class EnvironmentPathResolver : IPathResolver
{
    public EnvironmentPathResolver() : this(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
        Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
        AppContext.BaseDirectory) { }
    public EnvironmentPathResolver(string? localAppData, string? home, string? managerDirectory = null)
    {
        LocalAppData = Path.GetFullPath(!string.IsNullOrEmpty(localAppData)
            ? localAppData : Path.Combine(home ?? throw new ArgumentException("User profile missing"), ".local"));
        // Manager lives in <chosen install root>/manager. User state stays
        // in the profile, independent of custom installer /DIR choices.
        ProgramsRoot = Directory.GetParent(Path.TrimEndingDirectorySeparator(
            Path.GetFullPath(managerDirectory ?? AppContext.BaseDirectory)))?.FullName
            ?? throw new ArgumentException("Manager directory has no parent");
    }
    public string LocalAppData { get; }
    public string ProgramsRoot { get; }
    public string ManagerStateDir => Path.Combine(LocalAppData, "VibertemisVRHostManager");
    public string CompanionStateDir => Path.Combine(LocalAppData, "vibertemis", "companion");
    public string UpdateCacheDir => Path.Combine(ManagerStateDir, "update");
    public string LogDir => Path.Combine(ManagerStateDir, "logs");
}
