namespace VibertemisManager.Core.Integrity;
public static class InstalledPayload
{
    public static readonly string[] NativePaths = {
        "runtime/ALVR Dashboard.exe", "runtime/driver.vrdrivermanifest",
        "runtime/bin/win64/driver_alvr_server.dll", "runtime/bin/win64/openvr_api.dll",
        "runtime/bin/win64/pyrowave-shared.dll"
    };
    // The bundled vc_redist.x64.exe ships under prerequisites/ and is
    // verified through VcRedistVerifier (Authenticode + manifest SHA-256 +
    // size) before any install attempt. We list it in RequiredPaths so the
    // integrity verifier proves the bundled file matches the manifest.
    public static readonly string[] PrerequisitePaths = {
        "manager/prerequisites/vc_redist.x64.exe"
    };
    public static readonly string[] RequiredPaths = [..NativePaths, ..PrerequisitePaths,
        "manager/bin/vibertemis-host-companion.exe", "manager/VibertemisNetworkHelper.exe"];
}
