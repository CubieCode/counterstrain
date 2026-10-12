"""tools/check-provenance.py, loaded once as a module: the editor reuses its ledger row parser, PNG decoder
and placeholder-marker rule so it can never disagree with the CI check."""
import importlib.util
import os

_PATH = os.path.join(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))),
                     "check-provenance.py")
_spec = importlib.util.spec_from_file_location("check_provenance", _PATH)
provenance = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(provenance)
