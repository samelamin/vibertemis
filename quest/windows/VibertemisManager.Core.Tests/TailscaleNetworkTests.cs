using System.Net;
using VibertemisManager.Core.Network;
using Xunit;

namespace VibertemisManager.Core.Tests;
public sealed class TailscaleNetworkTests
{
    [Theory]
    [InlineData("100.64.0.1")]
    [InlineData("100.127.255.254")]
    public void CanonicalVpnAddressAccepted(string value) => Assert.Equal(value, TailscaleNetwork.ParseAddress(value).ToString());

    [Theory]
    [InlineData("100.63.255.255")]
    [InlineData("100.128.0.1")]
    [InlineData("127.0.0.1")]
    [InlineData("192.168.1.1")]
    [InlineData("100.64.0.1 & whoami")]
    [InlineData("100.064.0.1")]
    [InlineData("::ffff:100.64.0.1")]
    [InlineData("anything")]
    public void OtherOrNoncanonicalAddressRejected(string value) => Assert.Throws<ArgumentException>(()=>TailscaleNetwork.ParseAddress(value));

    [Fact] public void AddressAloneDoesNotIdentifyVpnAdapter()
    {
        var wifi = new NetworkAdapter("1", "Wi-Fi", "Cellular hotspot", IPAddress.Parse("100.64.0.1"), IPAddress.Parse("255.255.255.0"),true,true);
        Assert.False(wifi.IsTailscale);
        Assert.True((wifi with { Name="Tailscale" }).IsTailscale);
        Assert.False((wifi with { Name="Tailscale", Address=IPAddress.Loopback }).IsTailscale);
    }
}
