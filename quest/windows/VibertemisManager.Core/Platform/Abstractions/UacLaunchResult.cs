namespace VibertemisManager.Core.Platform.Abstractions;
public sealed record UacLaunchResult(bool Launched, int ProcessId, string Error, bool Completed = false, int? ExitCode = null);
