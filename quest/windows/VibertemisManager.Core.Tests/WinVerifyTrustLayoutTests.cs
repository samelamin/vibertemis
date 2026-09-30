// Native ABI layout tests for the WinVerifyTrust interop
// structs. The Microsoft WINTRUST_DATA / WINTRUST_FILE_INFO
// fields are P/Invoke ABI contracts - if our struct layout
// drifts from the documented wintrust.h declarations on x64,
// WinVerifyTrust will read garbage from the unmanaged side and
// silently fail verification.
//
// The expected sizes are derived from the official Microsoft
// docs (see https://learn.microsoft.com/en-us/windows/win32/
// api/wintrust/ns-wintrust-wintrust_data and .../wintrust_file_info):
//
//   WINTRUST_FILE_INFO (x64):
//     DWORD cbStruct;            // 4
//     [4 bytes padding]
//     LPCWSTR pcwszFilePath;     // 8 (pointer)
//     HANDLE  hFile;             // 8
//     GUID   *pgKnownSubject;    // 8
//     total                      // 32
//
//   WINTRUST_DATA (x64):
//     DWORD cbStruct;            // 4
//     [4 bytes padding]
//     LPVOID pPolicyCallbackData;// 8
//     LPVOID pSIPClientData;     // 8
//     DWORD  dwUIChoice;         // 4
//     DWORD  fdwRevocationChecks;// 4
//     DWORD  dwUnionChoice;      // 4
//     [4 bytes padding]
//     union { ... };             // 8 (pointer)
//     DWORD  dwStateAction;      // 4
//     [4 bytes padding]
//     HANDLE hWVTStateData;      // 8
//     LPCWSTR pwszURLReference;  // 8
//     DWORD  dwProvFlags;        // 4
//     DWORD  dwUIContext;        // 4
//     WINTRUST_SIGNATURE_SETTINGS *pSignatureSettings; // 8
//     total                      // 88
//
// We assert against the actual Marshal.SizeOf to fail fast if
// the structs are reordered. The structs themselves are
// private; the assertion runs through reflection so the layout
// can change without breaking test compilation.
using System;
using System.Reflection;
using VibertemisManager.Core.Prerequisites;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class WinVerifyTrustLayoutTests
{
    [Fact]
    public void Size_OfVcVersion_Struct_Is16()
    {
        Assert.Equal(16, System.Runtime.InteropServices.Marshal.SizeOf(typeof(VcVersion)));
    }

    [Fact]
    public void Verify_MicrosoftPublisher_MatchesLeafSubjectExact()
    {
        // Exact match passes.
        Assert.True(VcRedistVerifier.LeafSubjectMatchesMicrosoft(
            "CN=Microsoft Corporation, O=Microsoft Corporation, L=Redmond, S=Washington, C=US"));
        // Substring on the wrong DN component fails (we require O=, not CN).
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(
            "CN=Microsoft Corporation, L=Redmond"));
        // Foreign organization fails.
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(
            "CN=Contoso, O=Contoso Corporation"));
        // Issuer-style DN (CA) fails - we only check leaf O.
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(
            "CN=Microsoft Code Signing PCA, O=Microsoft Code Signing PCA"));
        // Empty / null fails.
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(null));
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(""));
        // Case-sensitive O= match.
        Assert.False(VcRedistVerifier.LeafSubjectMatchesMicrosoft(
            "O=microsoft corporation"));
    }

#if WINDOWS
    [Fact]
    public void WinTrust_FileInfo_NativeLayout_MatchesMicrosoftAbi_OnX64()
    {
        // Reflective access to the private struct so layout
        // changes inside the platform binding do not break this
        // assertion.
        var t = typeof(VibertemisManager.Core.Platform.Windows.WindowsVcRedistInspector)
            .GetNestedType("WINTRUST_FILE_INFO", BindingFlags.NonPublic);
        Assert.NotNull(t);
        var size = System.Runtime.InteropServices.Marshal.SizeOf(t!);
        Assert.Equal(32, size);
    }

    [Fact]
    public void WinTrust_Data_NativeLayout_MatchesMicrosoftAbi_OnX64()
    {
        var t = typeof(VibertemisManager.Core.Platform.Windows.WindowsVcRedistInspector)
            .GetNestedType("WINTRUST_DATA", BindingFlags.NonPublic);
        Assert.NotNull(t);
        var size = System.Runtime.InteropServices.Marshal.SizeOf(t!);
        Assert.Equal(88, size);
    }

    [Fact]
    public void WinTrust_FileInfo_FieldOrder_MatchesMicrosoftAbi()
    {
        // cbStruct, pcwszFilePath, hFile, pgKnownSubject.
        var t = typeof(VibertemisManager.Core.Platform.Windows.WindowsVcRedistInspector)
            .GetNestedType("WINTRUST_FILE_INFO", BindingFlags.NonPublic)!;
        var names = new System.Collections.Generic.List<string>();
        foreach (var f in t.GetFields(BindingFlags.Public | BindingFlags.Instance))
            names.Add(f.Name);
        Assert.Equal(new[] { "cbStruct", "pcwszFilePath", "hFile", "pgKnownSubject" }, names);
    }

    [Fact]
    public void WinTrust_Data_FieldOrder_MatchesMicrosoftAbi()
    {
        // Microsoft ABI field order, top to bottom.
        var t = typeof(VibertemisManager.Core.Platform.Windows.WindowsVcRedistInspector)
            .GetNestedType("WINTRUST_DATA", BindingFlags.NonPublic)!;
        var names = new System.Collections.Generic.List<string>();
        foreach (var f in t.GetFields(BindingFlags.Public | BindingFlags.Instance))
            names.Add(f.Name);
        Assert.Equal(new[]
        {
            "cbStruct", "pPolicyCallbackData", "pSIPClientData",
            "dwUIChoice", "fdwRevocationChecks", "dwUnionChoice",
            "pFile",
            "dwStateAction", "hWVTStateData", "pwszURLReference",
            "dwProvFlags", "dwUIContext", "pSignatureSettings",
        }, names);
    }

    [Theory]
    [InlineData("CN=Microsoft Corporation, O=Microsoft Corporation, C=US", "Microsoft Corporation")]
    [InlineData("CN=\"Foreign, O=Microsoft Corporation\", O=Other", "Other")]
    public void PublisherUsesEncodedOrganization(string subject, string expected)
    {
        var method = typeof(VibertemisManager.Core.Platform.Windows.WindowsVcRedistInspector)
            .GetMethod("Organization", BindingFlags.NonPublic | BindingFlags.Static)!;
        var encoded = new System.Security.Cryptography.X509Certificates.X500DistinguishedName(subject);
        Assert.Equal(expected, method.Invoke(null, new object[] { encoded.RawData }));
    }
#endif
}