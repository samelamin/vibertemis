// Network adapter discovery for the manager UI. The UI shows the
// user a list of reachable, non-loopback, non-virtual IPv4
// interfaces so they can pick which one the companion should bind
// to. Production uses System.Net.NetworkInformation on Windows;
// POSIX tests provide a fake IAdapterEnumerator so the UI logic can
// still be exercised on Linux.
using System.Collections.Generic;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace VibertemisManager.Core.Network;

public sealed record NetworkAdapter(
    string Id,
    string Name,
    string Description,
    IPAddress Address,
    IPAddress SubnetMask,
    bool IsUp,
    bool IsOperational)
{
    /// <summary>
    /// Compute the broadcast/peer range used for the local subnet
    /// scope on the Windows Firewall rule. We never hand this to
    /// netsh; the helper exe formats it itself from the structured
    /// adapter fields.
    /// </summary>
    public IReadOnlyList<string> LocalSubnetScope()
    {
        if (SubnetMask.AddressFamily != AddressFamily.InterNetwork) return new List<string>();
        var mask = SubnetMask.GetAddressBytes();
        var addr = Address.GetAddressBytes();
        var network = new byte[4];
        for (var i = 0; i < 4; i++) network[i] = (byte)(addr[i] & mask[i]);
        var cidr = 0;
        for (var i = 0; i < 4; i++)
            for (var b = 0; b < 8; b++)
                if ((mask[i] & (1 << (7 - b))) != 0) cidr++;
                else break;
        var cidrText = $"{string.Join('.', network)}/{cidr}";
        return new List<string> { cidrText };
    }

    public bool IsTailscale => TailscaleNetwork.IsAdapter(Name, Description) && TailscaleNetwork.IsAddress(Address);
    public override string ToString() => $"{Name} ({Address})" + (IsTailscale ? " — VPN" : "");
}

public interface IAdapterEnumerator
{
    IReadOnlyList<NetworkAdapter> Enumerate();
}

public sealed class SystemAdapterEnumerator : IAdapterEnumerator
{
    public IReadOnlyList<NetworkAdapter> Enumerate()
    {
        var result = new List<NetworkAdapter>();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            var props = ni.GetIPProperties();
            foreach (var ua in props.UnicastAddresses)
            {
                if (ua.Address.AddressFamily != AddressFamily.InterNetwork) continue;
                if (IPAddress.IsLoopback(ua.Address)) continue;
                if (ua.Address.IsIPv6LinkLocal || ua.Address.IsIPv6SiteLocal) continue;
                if (IsVirtualAdapter(ni)) continue;
                result.Add(new NetworkAdapter(
                    ni.Id,
                    ni.Name,
                    ni.Description,
                    ua.Address,
                    ua.IPv4Mask ?? SubnetFromPrefix(ua.PrefixLength),
                    ni.OperationalStatus == OperationalStatus.Up,
                    ni.OperationalStatus == OperationalStatus.Up));
            }
        }
        return result;
    }

    private static bool IsVirtualAdapter(NetworkInterface ni)
    {
        var desc = ni.Description ?? "";
        var name = ni.Name ?? "";
        if (TailscaleNetwork.IsAdapter(name, desc)) return false;
        // Conservative heuristic: ignore well-known virtual adapters
        // so the user doesn't accidentally bind to one. Real
        // Hyper-V / WSL bridges still show up; only the ones that
        // routinely break local-subnet discovery are excluded.
        foreach (var token in new[] { "VirtualBox", "VMware", "VPN", "Tailscale", "ZeroTier", "Hyper-V Virtual Switch" })
        {
            if (desc.Contains(token, System.StringComparison.OrdinalIgnoreCase)) return true;
            if (name.Contains(token, System.StringComparison.OrdinalIgnoreCase)) return true;
        }
        return false;
    }

    private static IPAddress SubnetFromPrefix(int prefix)
    {
        var bytes = new byte[4];
        for (var i = 0; i < 4; i++)
        {
            var remaining = System.Math.Max(0, prefix - i * 8);
            var bits = (byte)System.Math.Min(8, remaining);
            bytes[i] = (byte)(bits == 0 ? 0 : (0xFF << (8 - bits)) & 0xFF);
        }
        return new IPAddress(bytes);
    }
}
