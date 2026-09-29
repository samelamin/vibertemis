namespace VibertemisManager.Core.Integrity;
public static class InstalledPayload
{
    public static readonly string[] NativePaths = {
        "runtime/ALVR Dashboard.exe", "runtime/driver.vrdrivermanifest",
        "runtime/bin/win64/driver_alvr_server.dll", "runtime/bin/win64/openvr_api.dll",
        "runtime/bin/win64/pyrowave-shared.dll", "runtime/bin/win64/vcruntime140_1.dll"
    };
    public static readonly string[] RequiredPaths = [..NativePaths,
        "manager/bin/vibertemis-host-companion.exe", "manager/VibertemisNetworkHelper.exe"];
}
