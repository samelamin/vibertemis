using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;

namespace VibertemisManager.Core.Network;

public static class TailscaleNetwork
{
    public static bool IsAdapter(string name, string description) =>
        name.Contains("Tailscale", StringComparison.OrdinalIgnoreCase) ||
        description.Contains("Tailscale", StringComparison.OrdinalIgnoreCase);

    public static bool IsAddress(IPAddress address)
    {
        if (address.AddressFamily != AddressFamily.InterNetwork) return false;
        var bytes = address.GetAddressBytes();
        return bytes[0] == 100 && bytes[1] >= 64 && bytes[1] <= 127;
    }

    public static IPAddress ParseAddress(string text)
    {
        if (!IPAddress.TryParse(text, out var address) || !IsAddress(address) || address.ToString() != text)
            throw new ArgumentException("Choose the PC's Tailscale IPv4 address.");
        return address;
    }

    // Revalidate after elevation; an address in the shared CGNAT range alone
    // does not prove that the selected interface is the VPN adapter.
    public static string ValidateLocalAddress(string text)
    {
        var address = ParseAddress(text);
        foreach (var adapter in NetworkInterface.GetAllNetworkInterfaces()) {
            if (adapter.OperationalStatus != OperationalStatus.Up || !IsAdapter(adapter.Name, adapter.Description)) continue;
            if (adapter.GetIPProperties().UnicastAddresses.Any(a => a.Address.Equals(address))) return address.ToString();
        }
        throw new InvalidOperationException("That Tailscale address is not active on this PC. Connect the VPN and retry.");
    }
}
