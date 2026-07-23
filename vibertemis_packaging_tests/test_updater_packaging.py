from ._loader import load_canonical_test


_canonical = load_canonical_test(
    "test_updater_packaging",
    "test_updater_packaging.py",
)
UpdaterQmlContractTests = _canonical.UpdaterQmlContractTests
UpdateManifestGeneratorTests = _canonical.UpdateManifestGeneratorTests
