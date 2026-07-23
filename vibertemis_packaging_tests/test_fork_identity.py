from ._loader import load_canonical_test


_canonical = load_canonical_test(
    "test_fork_identity",
    "test_fork_identity.py",
)
ForkIdentityTests = _canonical.ForkIdentityTests
