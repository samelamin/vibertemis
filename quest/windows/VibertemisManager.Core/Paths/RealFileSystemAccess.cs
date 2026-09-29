// Default filesystem access. All access goes through this thin
// adapter so unit tests can supply an in-memory fake. No shell
// parsing, no implicit encoding detection.
using System.Collections.Generic;
using System.IO;
using VibertemisManager.Core.Platform.Abstractions;

namespace VibertemisManager.Core.Paths;

public sealed class RealFileSystemAccess : IFileSystemAccess
{
    public bool FileExists(string path) => File.Exists(path);

    public string ReadAllText(string path) => File.ReadAllText(path);

    public bool DirectoryExists(string path) => Directory.Exists(path);

    public IEnumerable<string> EnumerateFiles(string directory, string pattern)
        => Directory.EnumerateFiles(directory, pattern);
}