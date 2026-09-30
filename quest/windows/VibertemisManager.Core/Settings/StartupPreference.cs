namespace VibertemisManager.Core.Settings;

public static class StartupPreference
{
    // Legacy settings have no marker. Preserve both false and true choices;
    // only a genuinely missing settings file receives the recommended default.
    public static bool IsEnabled(UserSettings settings) => settings.KeepHostReadyAfterSignIn ?? settings.AutoStartWithWindows;
    public static void Apply(UserSettings settings, bool enabled)
    {
        settings.KeepHostReadyAfterSignIn = enabled;
        settings.StartupPreferencePersisted = true;
        settings.AutoStartWithWindows = enabled;
        settings.RestoreCompanionOnStartup = enabled;
    }
}
