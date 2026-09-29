// Network adapter enumeration / subnet scope tests.
//
// LocalSubnetScope produces CIDR notation for use in the firewall
// rule's remoteip= field. The format must be deterministic and
// match Windows' netsh parsing of the same notation.
using System.Net;
using VibertemisManager.Core.Network;
using Xunit;

namespace VibertemisManager.Core.Tests;

public class NetworkAdapterTests
{
    [Fact]
    public void LocalSubnetScope_ComputesCidr()
    {
        var adapter = new NetworkAdapter(
            "id1", "eth0", "Test",
            IPAddress.Parse("192.168.1.42"),
            IPAddress.Parse("255.255.255.0"),
            true, false);
        var scopes = adapter.LocalSubnetScope();
        Assert.Single(scopes);
        Assert.Equal("192.168.1.0/24", scopes[0]);
    }

    [Fact]
    public void LocalSubnetScope_HandlesSlashThirty()
    {
        var adapter = new NetworkAdapter(
            "id1", "eth0", "Test",
            IPAddress.Parse("10.0.0.5"),
            IPAddress.Parse("255.255.255.252"),
            true, false);
        Assert.Equal("10.0.0.4/30", adapter.LocalSubnetScope()[0]);
    }

    [Fact]
    public void LocalSubnetScope_HandlesClassB()
    {
        var adapter = new NetworkAdapter(
            "id1", "eth0", "Test",
            IPAddress.Parse("172.16.5.99"),
            IPAddress.Parse("255.255.0.0"),
            true, false);
        Assert.Equal("172.16.0.0/16", adapter.LocalSubnetScope()[0]);
    }

    [Fact]
    public void LocalSubnetScope_HandlesClassA()
    {
        var adapter = new NetworkAdapter(
            "id1", "eth0", "Test",
            IPAddress.Parse("10.0.0.1"),
            IPAddress.Parse("255.0.0.0"),
            true, false);
        Assert.Equal("10.0.0.0/8", adapter.LocalSubnetScope()[0]);
    }

    [Fact]
    public void ToString_ShowsNameAndAddress()
    {
        var adapter = new NetworkAdapter(
            "id1", "eth0", "Test",
            IPAddress.Parse("192.168.1.42"),
            IPAddress.Parse("255.255.255.0"),
            true, false);
        Assert.Equal("eth0 (192.168.1.42)", adapter.ToString());
    }
}