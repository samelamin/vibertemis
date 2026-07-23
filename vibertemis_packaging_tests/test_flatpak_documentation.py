from ._loader import load_canonical_test


_canonical = load_canonical_test(
    "test_flatpak_documentation",
    "test_flatpak_documentation.py",
)
FlatpakDocumentationTests = _canonical.FlatpakDocumentationTests
