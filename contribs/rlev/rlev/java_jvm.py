# rlev/java_jvm.py
from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Optional

def _find_project_root() -> Path:
    """Locate the Maven module root (the dir that contains pom.xml)."""
    here = Path(__file__).resolve()
    for p in [here.parent, here.parent.parent, here.parent.parent.parent, here.parent.parent.parent.parent]:
        if (p / "pom.xml").exists():
            return p
    return here.parent.parent  # fallback: contribs/rlev

def _java_cmd() -> str:
    """Pick a java launcher on PATH."""
    return "java.exe" if os.name == "nt" else "java"

def _mvn_cmd() -> list[str]:
    """
    Return a Maven command if present; otherwise empty list.
    We still allow 'mvn' to build the jar once, but we don't use exec:java.
    """
    candidates = []
    if os.name == "nt":
        # Favor the conda-bundled mvn if present
        conda_mvn = Path(os.environ.get("CONDA_PREFIX", "")) / "Library" / "bin" / "mvn.cmd"
        if conda_mvn.exists():
            candidates.append(str(conda_mvn))
        candidates.extend(["mvn.cmd", "mvn"])
    else:
        candidates.extend(["mvn"])
    for c in candidates:
        if shutil.which(c):
            return [shutil.which(c)]  # type: ignore[arg-type]
    return []

def _build_uber_jar(project_root: Path) -> bool:
    """Build the shaded jar once."""
    mvn = _mvn_cmd()
    if not mvn:
        print("[java_jvm] WARN: Maven not found on PATH; cannot build uber-jar automatically.")
        return False
    cmd = mvn + ["-q", "-U", "clean", "package"]
    print(f"[java_jvm] building uber-jar: {' '.join(cmd)} (cwd={project_root})", flush=True)
    proc = subprocess.run(cmd, cwd=str(project_root), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if proc.returncode != 0:
        print(proc.stdout)
        print("[java_jvm] ERROR: Maven build failed.")
        return False
    return True

def run_controler(config_path: Path | str,
                  output_dir: Path | str,
                  fast_opts: bool = True,
                  end_time_sec: Optional[int] = None) -> tuple[int, str]:
    """
    Run MATSim via a shaded jar (target/rlev-uber.jar), avoiding mvn exec:java.
    Returns (returncode, stdout).
    """
    config_path = Path(config_path).resolve()
    output_dir = Path(output_dir).resolve()
    output_dir.mkdir(parents=True, exist_ok=True)

    project_root = _find_project_root()
    jar_path = project_root / "target" / "rlev-uber.jar"

    # Auto-build the jar if missing
    if not jar_path.exists():
        if not _build_uber_jar(project_root):
            return 1, "failed to build rlev-uber.jar"

    # Prepare arguments for RLLauncher
    #   <config> <outputDir> <fastOpts(true/false)> [endTimeSec]
    args = [
        str(config_path),
        str(output_dir),
        "true" if fast_opts else "false",
    ]
    if end_time_sec is not None and int(end_time_sec) > 0:
        args.append(str(int(end_time_sec)))

    java = _java_cmd()
    cmd = [java, "-jar", str(jar_path)] + args
    print(f"[java_jvm] running: {' '.join(cmd)}", flush=True)

    proc = subprocess.run(
        cmd,
        cwd=str(project_root),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        env=os.environ.copy(),
    )
    # Surface Java output for debugging
    print(proc.stdout)
    return proc.returncode, proc.stdout
