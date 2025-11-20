"""Minimal JPype bridge for invoking RLBridge inside the shaded MATSim jar."""

from __future__ import annotations

import os
import threading
from pathlib import Path
from typing import Any, Dict, Optional
import os
import shutil
import subprocess

try:
    import jpype
    import jpype.imports  # noqa: F401  (needed for class imports)
    from jpype import JException
except ImportError as exc:  # pragma: no cover - import guard
    raise RuntimeError(
        "jpype1 is required for the embedded MATSim backend. "
        "Install it via `pip install jpype1`."
    ) from exc

from rlev.java_jvm import ensure_uber_jar  # reuse existing helpers

_JVM_LOCK = threading.Lock()
_JVM_STARTED = False
_RL_BRIDGE_CLASS = None
_JVMPATH: Optional[Path] = None


def _detect_jvm_dll() -> Path:
    # explicit override
    override = os.environ.get("RLEV_JVM_DLL")
    if override:
        dll = Path(override)
        if dll.exists():
            return dll
        raise FileNotFoundError(f"RLEV_JVM_DLL points to missing file: {dll}")

    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        dll = Path(java_home) / "bin" / "server" / "jvm.dll"
        if dll.exists():
            return dll

    java_cmd = shutil.which("java.exe") or shutil.which("java")
    if java_cmd:
        try:
            proc = subprocess.run(
                [java_cmd, "-XshowSettings:properties", "-version"],
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
            )
            for line in proc.stdout.splitlines():
                line = line.strip()
                if line.lower().startswith("java.home"):
                    parts = line.split("=", 1)
                    if len(parts) == 2:
                        candidate = Path(parts[1].strip())
                        dll = candidate / "bin" / "server" / "jvm.dll"
                        if dll.exists():
                            return dll
        except Exception:
            pass

    raise RuntimeError("Unable to locate jvm.dll. Set JAVA_HOME or RLEV_JVM_DLL.")


def _start_jvm_if_needed(max_heap_gb: int = 6) -> None:
    global _JVM_STARTED
    if _JVM_STARTED and jpype.isJVMStarted():
        return

    with _JVM_LOCK:
        if _JVM_STARTED and jpype.isJVMStarted():
            return

        jar_path = ensure_uber_jar()
        global _JVMPATH
        if _JVMPATH is None:
            _JVMPATH = _detect_jvm_dll()
        classpath = str(jar_path)
        jpype.startJVM(
            "-ea",
            f"-Xmx{max_heap_gb}g",
            jvmpath=str(_JVMPATH),
            classpath=[classpath],
            convertStrings=True,
        )
        _JVM_STARTED = True


def _load_bridge_class():
    global _RL_BRIDGE_CLASS
    if _RL_BRIDGE_CLASS is not None:
        return _RL_BRIDGE_CLASS
    _start_jvm_if_needed()
    from org.matsim.contrib.rlev import RLBridge  # type: ignore[attr-defined]

    _RL_BRIDGE_CLASS = RLBridge
    return _RL_BRIDGE_CLASS


def run_iteration(
    config_path: Path | str,
    output_dir: Path | str,
    *,
    fast_opts: bool = False,
    end_time_sec: Optional[int] = None,
    seed: Optional[int] = None,
) -> Dict[str, Any]:
    """
    Invoke RLBridge.run(...) inside the JVM and return a Python dict with the
    telemetry reported by RewardProbe.
    """
    bridge = _load_bridge_class()
    config_path = Path(config_path).resolve()
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    end_time = int(end_time_sec) if end_time_sec is not None else -1
    sim_seed = int(seed) if seed is not None else 0

    try:
        metrics = bridge.run(
            str(config_path),
            str(output_dir),
            bool(fast_opts),
            end_time,
            sim_seed,
        )
    except JException as exc:  # pragma: no cover - surface JVM stack traces
        stack = "".join(exc.stacktrace()) if hasattr(exc, "stacktrace") else ""
        raise RuntimeError(
            f"MATSim run failed with {exc.__class__.__name__}: {exc}\n{stack}"
        ) from exc

    return {
        "energy_kwh": float(metrics.getEnergyChargedKWh()),
        "avg_leg_duration_sec": float(metrics.getAvgLegDurationSec()),
        "avg_queue_time_sec": float(metrics.getAvgQueueTimeSec()),
        "avg_charging_duration_sec": float(metrics.getAvgChargingDurationSec()),
        "completed_charges": int(metrics.getCompletedCharges()),
    }
