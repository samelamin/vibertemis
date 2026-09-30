#if WINDOWS
using System.Diagnostics;
using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Win32;
using VibertemisManager.Core.Bridge;
using VibertemisManager.Core.Platform.Windows;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class WindowsBridgeSmokeTests
{
    [Fact]
    public void RegistrationCanBeRewrittenAndHasProtectedAcl()
    {
        var subkey = @"SOFTWARE\VibertemisTests\" + Guid.NewGuid().ToString("N");
        using var machine = RegistryKey.OpenBaseKey(RegistryHive.LocalMachine, RegistryView.Registry64);
        using var identity = WindowsIdentity.GetCurrent();
        var sid = identity.User!.Value;
        try {
            var writer = new WindowsBridgeKeyWriter();
            Assert.Equal(BridgeWriteOutcome.Written, writer.Write("HKLM",subkey,@"C:\test\companion.exe",@"C:\host\sunshine.exe",sid));
            Assert.Equal(BridgeWriteOutcome.Written, writer.Write("HKLM",subkey,@"C:\test\companion2.exe",@"C:\host\sunshine.exe",sid));
            Assert.Equal(@"C:\test\companion2.exe",new WindowsBridgeKeyReader().Read("HKLM",subkey).CompanionPath);
            using var key = machine.OpenSubKey(subkey)!;
            var acl = key.GetAccessControl();
            Assert.True(acl.AreAccessRulesProtected);
            foreach (RegistryAccessRule rule in acl.GetAccessRules(true,true,typeof(SecurityIdentifier))) {
                Assert.False(rule.IsInherited);
                var principal = rule.IdentityReference.Value;
                Assert.Contains(principal,new[]{sid,"S-1-5-18","S-1-5-32-544"});
                if(principal==sid && sid!="S-1-5-18") Assert.Equal(RegistryRights.ReadKey,rule.RegistryRights);
            }
        } finally { machine.DeleteSubKeyTree(subkey,throwOnMissingSubKey:false); }
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void ServiceDiscoveryReadsActualScmPath(bool direct)
    {
        string root=Path.Combine(Path.GetTempPath(),"Vibertemis service test "+Guid.NewGuid().ToString("N"));
        string service="VibertemisTest"+Guid.NewGuid().ToString("N");
        Directory.CreateDirectory(Path.Combine(root,"tools"));
        string host=Path.Combine(root,"sunshine.exe");
        string binary=direct?host:Path.Combine(root,"tools","sunshinesvc.exe");
        File.WriteAllBytes(host,new byte[]{1});
        if(!direct)File.WriteAllBytes(binary,new byte[]{1});
        bool created=false;
        try {
            Assert.Equal(0,Sc("create",service,"binPath=","\""+binary+"\" --test","start=","demand"));
            created=true;
            var result=new WindowsBridgeScmProbe().ResolveSunshine(service,"sunshine.exe");
            Assert.Equal(ScmQueryStatus.Resolved,result.Status);
            Assert.Equal(host,result.InstalledExePath);
        } finally {
            if(created)Assert.Equal(0,Sc("delete",service));
            Directory.Delete(root,true);
        }
    }
    private static int Sc(params string[] args)
    {
        var start=new ProcessStartInfo(Path.Combine(Environment.SystemDirectory,"sc.exe")){UseShellExecute=false,CreateNoWindow=true};
        foreach(var arg in args)start.ArgumentList.Add(arg);
        using var process=Process.Start(start)!;
        Assert.True(process.WaitForExit(10000),"Service control command timed out");
        return process.ExitCode;
    }
}
#endif
