from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]


def load_canonical_test(module_name, filename):
    path = REPOSITORY_ROOT / "packaging" / "flatpak" / "tests" / filename
    spec = spec_from_file_location(
        f"_vibertemis_canonical_{module_name}",
        path,
    )
    if spec is None or spec.loader is None:
        raise ImportError(f"Cannot load canonical test module: {path}")
    module = module_from_spec(spec)
    spec.loader.exec_module(module)
    return module
