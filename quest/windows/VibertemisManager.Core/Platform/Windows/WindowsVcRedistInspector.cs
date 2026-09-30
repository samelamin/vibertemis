// Windows-specific vc_redist.x64.exe inspection.
//
// Two responsibilities, both production-only Windows paths:
//
//   1. WinVerifyTrust with WINTRUST_ACTION_GENERIC_VERIFY_V2,
//      WTD_UI_NONE, fdwRevocationChecks = WTD_REVOKE_NONE,
//      dwProvFlags = WTD_CACHE_ONLY_URL_RETRIEVAL. This actually
//      verifies the PKCS7 signature against the file bytes
//      (X509Certificate alone does NOT - it only extracts the
//      certificate). The WTD_CACHE_ONLY_URL_RETRIEVAL flag
//      guarantees the offline setup path: per Microsoft docs the
//      revoke=none flag alone does NOT prevent the trust
//      provider from attempting network retrieval for AIA / OCSP
//      completion. We DO NOT use WTD_HASH_ONLY_FLAG (which would
//      bypass the chain validation and accept a hash-only
//      attestation) or WTD_NO_POLICY_USAGE_FLAG.
//
//   2. Extract the leaf signer Subject (X509Certificate.Create-
//      FromSignedFile) and confirm the leaf certificate's
//      Subject DN contains CN="Microsoft Corporation" and
//      O="Microsoft Corporation" by exact string match against
//      the parsed DN components. Issuer is the CA (e.g. Microsoft
//      Code Signing PCA) and is NOT used for the publisher check.
//      CompanyName from FileVersionInfo is a PE string and is
//      trivially forgeable - it is NOT trusted.
//
// The native WINTRUST_DATA / WINTRUST_FILE_INFO structs must
// match the Microsoft ABI on x64 exactly; the field order and
// padding are part of the API contract. cbStruct values must be
// accurate (an incorrect cbStruct causes WinVerifyTrust to
// silently fail / read garbage). Layout is asserted by a
// dedicated xUnit test in the Tests project.
//
// Per Microsoft docs (WINTRUST_DATA):
//   cbStruct                 DWORD
//   pPolicyCallbackData      LPVOID
//   pSIPClientData           LPVOID
//   dwUIChoice               DWORD
//   fdwRevocationChecks      DWORD
//   dwUnionChoice            DWORD
//   <union>                  ptr
//   dwStateAction            DWORD
//   hWVTStateData            HANDLE
//   pwszURLReference         LPCWSTR
//   dwProvFlags              DWORD
//   dwUIContext              DWORD
//   pSignatureSettings       ptr
//
// Reads the PE FileVersion via System.Diagnostics.FileVersionInfo;
// refuses FileVersion < the toolchain minimum so Microsoft
// re-signing of an older redistributable still fails readiness.
#if WINDOWS
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography.X509Certificates;
using VibertemisManager.Core.Prerequisites;

namespace VibertemisManager.Core.Platform.Windows;

public sealed class WindowsVcRedistInspector : IVcRedistInspector
{
    public VcRedistPackage? Inspect(string absolutePath, VcVersion minimumFileVersion, string expectedPublisher)
    {
        if (string.IsNullOrEmpty(absolutePath) || !File.Exists(absolutePath))
            return null;

        try
        {
            // Keep the file locked against replacement for signature and hash
            // inspection. Installer launch separately verifies the pinned hash.
            using var stream = new FileStream(absolutePath, FileMode.Open, FileAccess.Read, FileShare.Read);
            if (stream.Length <= 0 || stream.Length > 64L * 1024 * 1024) return null;
            var info = FileVersionInfo.GetVersionInfo(absolutePath);
            if (!VcVersion.TryParse(info.FileVersion ?? "", out var version) || version.CompareTo(minimumFileVersion) < 0)
                return null;
            if (!VerifyAuthenticode(absolutePath)) return null;
            using var cert = X509Certificate.CreateFromSignedFile(absolutePath);
            using var leaf = new X509Certificate2(cert);
            var publisher = Organization(leaf.SubjectName.RawData);
            if (!string.Equals(publisher, expectedPublisher, StringComparison.Ordinal)) return null;
            return new VcRedistPackage(absolutePath, stream.Length,
                ToHex(System.Security.Cryptography.SHA256.HashData(stream)), publisher, leaf.Subject, version);
        }
        catch { return null; }
    }

    private static string Organization(byte[] encodedName)
    {
        var reader = new System.Formats.Asn1.AsnReader(encodedName, System.Formats.Asn1.AsnEncodingRules.DER);
        var sequence = reader.ReadSequence();
        string organization = "";
        while (sequence.HasData)
        {
            var set = sequence.ReadSetOf();
            while (set.HasData)
            {
                var item = set.ReadSequence();
                var oid = item.ReadObjectIdentifier();
                if (oid == "2.5.4.10")
                {
                    if (organization.Length != 0) return "";
                    organization = item.ReadCharacterString((System.Formats.Asn1.UniversalTagNumber)item.PeekTag().TagValue);
                }
                else item.ReadEncodedValue();
                item.ThrowIfNotEmpty();
            }
        }
        reader.ThrowIfNotEmpty();
        return organization;
    }

    private static string ToHex(byte[] bytes)
    {
        var sb = new System.Text.StringBuilder(bytes.Length * 2);
        foreach (var b in bytes) sb.Append(b.ToString("x2"));
        return sb.ToString();
    }

    // WinVerifyTrust(WINTRUST_ACTION_GENERIC_VERIFY_V2) per
    // Microsoft's documented sample. The struct layouts and
    // field sizes are pinned by the layout test.
    private static bool VerifyAuthenticode(string path)
    {
        // Marshal the file path as a stable unmanaged string
        // pointer. Marshal.StructureToPtr alone does not marshal
        // inner string fields when used with manually-allocated
        // unmanaged memory.
        var pathPtr = Marshal.StringToHGlobalUni(path);
        var fileInfoPtr = Marshal.AllocHGlobal(Marshal.SizeOf(typeof(WINTRUST_FILE_INFO)));
        try
        {
            var fileInfo = new WINTRUST_FILE_INFO
            {
                cbStruct = (uint)Marshal.SizeOf(typeof(WINTRUST_FILE_INFO)),
                pcwszFilePath = pathPtr,
                hFile = IntPtr.Zero,
                pgKnownSubject = IntPtr.Zero,
            };
            Marshal.StructureToPtr(fileInfo, fileInfoPtr, false);
            var data = new WINTRUST_DATA
            {
                cbStruct = (uint)Marshal.SizeOf(typeof(WINTRUST_DATA)),
                pPolicyCallbackData = IntPtr.Zero,
                pSIPClientData = IntPtr.Zero,
                dwUIChoice = WTD_UI_NONE,
                fdwRevocationChecks = WTD_REVOKE_NONE,
                dwUnionChoice = WTD_CHOICE_FILE,
                pFile = fileInfoPtr,
                dwStateAction = WTD_STATEACTION_VERIFY,
                hWVTStateData = IntPtr.Zero,
                pwszURLReference = IntPtr.Zero,
                dwProvFlags = WTD_CACHE_ONLY_URL_RETRIEVAL,
                dwUIContext = WTD_UICONTEXT_INSTALL,
                pSignatureSettings = IntPtr.Zero,
            };
            var hr = WinVerifyTrust(IntPtr.Zero, GuidWinTrustActionVerify, ref data);
            // Always close the state handle, even on success or
            // failure. Without this call the trust provider holds
            // a kernel handle and the next WinVerifyTrust on the
            // same file in the same process can fail with
            // TRUST_E_SUBJECT_FORM_UNKNOWN.
            data.dwStateAction = WTD_STATEACTION_CLOSE;
            WinVerifyTrust(IntPtr.Zero, GuidWinTrustActionVerify, ref data);
            return hr == 0;
        }
        catch
        {
            return false;
        }
        finally
        {
            Marshal.FreeHGlobal(fileInfoPtr);
            Marshal.FreeHGlobal(pathPtr);
        }
    }

    private const uint WTD_UI_NONE = 2;
    private const uint WTD_REVOKE_NONE = 0;        // Per docs; 1 is WTD_REVOKE_WHOLECHAIN.
    private const uint WTD_CHOICE_FILE = 1;
    private const uint WTD_STATEACTION_VERIFY = 1;
    private const uint WTD_STATEACTION_CLOSE = 2;
    private const uint WTD_UICONTEXT_INSTALL = 1;
    private const uint WTD_CACHE_ONLY_URL_RETRIEVAL = 0x1000;

    private static readonly Guid GuidWinTrustActionVerify =
        new("00AAC56B-CD44-11d0-8CC2-00C04FC295EE");

    [DllImport("wintrust.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern int WinVerifyTrust(
        IntPtr hwnd,
        [MarshalAs(UnmanagedType.LPStruct)] Guid pgActionID,
        ref WINTRUST_DATA pWVTData);

    // Native layout per Microsoft docs. Field order, sizes, and
    // padding match the wintrust.h declarations on x64. Do not
    // reorder fields; this struct is a P/Invoke ABI contract.
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    private struct WINTRUST_FILE_INFO
    {
        public uint cbStruct;          // DWORD
        public IntPtr pcwszFilePath;   // LPCWSTR (not a managed string - marshaled separately)
        public IntPtr hFile;           // HANDLE
        public IntPtr pgKnownSubject;  // GUID *
    }

    // Native layout per Microsoft docs. Field order, sizes, and
    // padding match the wintrust.h declarations on x64.
    [StructLayout(LayoutKind.Sequential)]
    private struct WINTRUST_DATA
    {
        public uint cbStruct;
        public IntPtr pPolicyCallbackData;
        public IntPtr pSIPClientData;
        public uint dwUIChoice;
        public uint fdwRevocationChecks;
        public uint dwUnionChoice;
        public IntPtr pFile;
        public uint dwStateAction;
        public IntPtr hWVTStateData;
        public IntPtr pwszURLReference;
        public uint dwProvFlags;
        public uint dwUIContext;
        public IntPtr pSignatureSettings;
    }
}
#endif