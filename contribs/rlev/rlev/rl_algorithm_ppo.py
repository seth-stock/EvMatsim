"""
PPO training runner for MATSim-based envs (MLP/GNN/GraphGPS).

Non-optional Fix C:
- Add --backend flag and force it via RLEV_BACKEND env var so the Python env
  runs fully local when requested (no HTTP server unless you choose --backend server).
"""

import argparse
import os
import json
import random
import hashlib
import platform
from datetime import datetime
from pathlib import Path
from typing import Any

import gymnasium as gym
import numpy as np
import torch
from stable_baselines3 import PPO
from stable_baselines3.common.callbacks import BaseCallback, CallbackList, CheckpointCallback
from stable_baselines3.common.vec_env import DummyVecEnv

# Type-only imports for hints/logging
from rlev.envs.matsim_graph_env_gnn import MatsimGraphEnvGNN  # noqa: F401
from rlev.envs.matsim_graph_env_mlp import MatsimGraphEnvMlp  # noqa: F401

# GraphGPS extractor (your file lives next to this script)
from .graphgps_extractor import GraphGPSExtractor

# --- Guard for CUDA tensor actions in this SB3 fork ---
try:
    from stable_baselines3.common import buffers as _sb3_buf
    _orig_add = _sb3_buf.RolloutBuffer.add

    def _add_guard(self, obs, actions, *args, **kwargs):
        # Some forks pass torch tensors for actions; make sure we store numpy
        import numpy as _np
        import torch as _th
        if isinstance(actions, _th.Tensor):
            actions = actions.detach().cpu().numpy()
        else:
            # if it's a list/tuple of tensors (multi-discrete can be vector)
            if isinstance(actions, (list, tuple)):
                actions = _np.asarray([
                    a.detach().cpu().numpy() if isinstance(a, _th.Tensor) else a
                    for a in actions
                ])
        return _orig_add(self, obs, actions, *args, **kwargs)

    _sb3_buf.RolloutBuffer.add = _add_guard  # monkeypatch
except Exception as _e:
    print(f"[rollout-buffer-guard] non-fatal: {_e}", flush=True)



# --- SB3 rollout buffer patch: ensure actions are numpy on CPU ---------------
from stable_baselines3.common.buffers import RolloutBuffer as _RB
import inspect as _inspect

from rlev.java_jvm import ensure_uber_jar

if not getattr(_RB, "_rlev_patched", False):
    _RB__add_orig = _RB.add

    def _rlev_add(self, obs, action, reward, episode_start, value, log_prob):
        # Coerce action to numpy on CPU if it is a torch.Tensor
        try:
            import torch as _th
            if isinstance(action, _th.Tensor):
                action = action.detach().cpu().numpy()
        except Exception:
            pass
        return _RB__add_orig(self, obs, action, reward, episode_start, value, log_prob)

    # Verify signature matches (defensive)
    if len(_inspect.signature(_RB.add).parameters) == 6:
        _RB.add = _rlev_add
        _RB._rlev_patched = True
# ---------------------------------------------------------------------------


def _print_cuda_diag():
    try:
        info = dict(
            cuda_available=torch.cuda.is_available(),
            torch_cuda=torch.version.cuda,
            n_devices=torch.cuda.device_count(),
            current_device=(torch.cuda.current_device() if torch.cuda.is_available() else None),
            device_name=(torch.cuda.get_device_name(0) if torch.cuda.is_available() else None),
        )
        print(f"[CUDA DIAG] {info}", flush=True)
        return info
    except Exception as e:
        print(f"[CUDA DIAG] error: {e}", flush=True)
        return {"error": str(e)}


def _seed_everything(seed: int) -> None:
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    if torch.cuda.is_available():
        torch.cuda.manual_seed_all(seed)


def _write_manifest(save_dir: Path, args: argparse.Namespace, cuda_info: dict[str, Any]) -> None:
    def _to_jsonable(value):
        if isinstance(value, dict):
            return {k: _to_jsonable(v) for k, v in value.items()}
        if isinstance(value, (list, tuple)):
            return [_to_jsonable(v) for v in value]
        if isinstance(value, Path):
            return str(value)
        return value

    manifest = {
        "timestamp": datetime.utcnow().isoformat(timespec="seconds") + "Z",
        "cli_args": _to_jsonable(vars(args)),
        "seed": args.seed,
        "cuda": _to_jsonable(cuda_info),
        "python_version": platform.python_version(),
        "torch_version": torch.__version__,
        "run_directory": str(save_dir),
        "manifest_version": 1,
    }
    try:
        jar_path = ensure_uber_jar()
        jar_bytes = Path(jar_path).read_bytes()
        manifest["uber_jar"] = {
            "path": str(jar_path),
            "sha256": hashlib.sha256(jar_bytes).hexdigest(),
        }
    except Exception as exc:
        manifest["uber_jar"] = {"error": str(exc)}

    with open(save_dir / "manifest.json", "w", encoding="utf-8") as fp:
        json.dump(manifest, fp, indent=2)


class TensorboardCallback(BaseCallback):
    """Log averages and persist best snapshot. Expects info['graph_env_inst'] per env step."""

    def __init__(self, verbose: int = 0, save_dir: str | None = None):
        super().__init__(verbose)
        self.save_dir = save_dir
        self.best_reward = -np.inf
        self.best_env: Any = None

    def _on_step(self) -> bool:
        infos_list = self.locals.get("infos", []) or []
        if not infos_list:
            return True

        avg_reward = 0.0
        avg_cost = 0.0
        avg_charger_eff = 0.0
        avg_time_eff = 0.0
        avg_queue = 0.0
        avg_charge_duration = 0.0
        avg_energy = 0.0
        avg_completed = 0.0

        for infos in infos_list:
            env_inst = infos.get("graph_env_inst")
            if env_inst is None:
                continue

            reward = float(getattr(env_inst, "_reward", 0.0))
            avg_reward += reward
            avg_cost += float(getattr(env_inst, "_charger_cost", 0.0))
            avg_charger_eff += float(getattr(env_inst, "_charger_efficiency", 0.0))
            avg_time_eff += float(getattr(env_inst, "_time_efficiency", 0.0))
            avg_queue += float(getattr(env_inst, "_avg_queue_time_sec", 0.0))
            avg_charge_duration += float(getattr(env_inst, "_avg_charge_duration_sec", 0.0))
            avg_energy += float(getattr(env_inst, "_energy_kwh", 0.0))
            avg_completed += float(getattr(env_inst, "_completed_charges", 0.0))

            if reward > self.best_reward:
                self.best_reward = reward
                self.best_env = env_inst
                if self.save_dir is not None:
                    self.best_env.save_charger_config_to_csv(Path(self.save_dir, "best_chargers.csv"))
                    if getattr(self.best_env, "best_output_response", None) is not None:
                        self.best_env.save_server_output(self.best_env.best_output_response, "bestoutput")

        n = max(1, len(infos_list))
        self.logger.record("metrics/avg_reward", avg_reward / n)
        self.logger.record("metrics/best_reward", self.best_reward)
        self.logger.record("metrics/avg_charger_cost", avg_cost / n)
        self.logger.record("metrics/avg_charger_efficiency", avg_charger_eff / n)
        self.logger.record("metrics/avg_time_efficiency", avg_time_eff / n)
        self.logger.record("metrics/avg_queue_time_sec", avg_queue / n)
        self.logger.record("metrics/avg_charge_duration_sec", avg_charge_duration / n)
        self.logger.record("metrics/avg_energy_kwh", avg_energy / n)
        self.logger.record("metrics/avg_completed_charges", avg_completed / n)
        return True


def main(args: argparse.Namespace):
    # ---------- Fix C: force backend selection ----------
    os.environ["RLEV_BACKEND"] = args.backend  # consumed by MatsimGraphEnv*
    print(f"[ENV] RLEV_BACKEND={os.environ['RLEV_BACKEND']}", flush=True)

    # ---------- Output dir ----------
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    save_dir = Path(args.results_dir) / run_id
    save_dir.mkdir(parents=True, exist_ok=True)

    with open(save_dir / "args.txt", "w", encoding="utf-8") as f:
        for k, v in vars(args).items():
            f.write(f"{k}:{v}\n")

    cuda_info = _print_cuda_diag()
    _write_manifest(save_dir, args, cuda_info)

    # ---------- Env factory ----------
    def make_env(rank: int):
        def _init():
            make_kwargs = dict(
                config_path=args.matsim_config,
                num_agents=args.num_agents,
                save_dir=save_dir,
                backend=args.backend,
                fast_opts=args.fast_opts,
                seed=(args.seed + rank) if args.seed is not None else None,
            )
            if args.policy_type == "MlpPolicy":
                return gym.make("MatsimGraphEnvMlp-v0", **make_kwargs)
            elif args.policy_type == "GNNPolicy":
                return gym.make("MatsimGraphEnvGNN-v0", **make_kwargs)
            elif args.policy_type == "GraphGPS":
                return gym.make("MatsimGraphEnvGPS-v0", **make_kwargs)
            else:
                raise ValueError(f"Unknown policy_type: {args.policy_type}")
        return _init

    # Windows-friendly (no subprocess fork)
    env = DummyVecEnv([make_env(i) for i in range(args.num_envs)])

    # Save frequency is per-env; scale to total steps
    args.save_frequency //= max(1, args.num_envs)

    tensorboard_cb = TensorboardCallback(save_dir=save_dir)
    checkpoint_cb = CheckpointCallback(save_freq=args.save_frequency, save_path=str(save_dir))
    callback = CallbackList([tensorboard_cb, checkpoint_cb])

    # ---------- Device ----------
    if args.device == "cuda":
        device_str = "cuda:0"
    elif args.device == "cpu":
        device_str = "cpu"
    else:
        device_str = "cuda:0" if torch.cuda.is_available() else "cpu"
    print(f"[RL] Using device={device_str}", flush=True)

    # ---------- Policy + kwargs ----------
    policy_id: str = args.policy_type
    policy_kwargs: dict[str, Any] = dict(net_arch=args.mlp_dims)

    if args.policy_type == "GraphGPS":
        policy_id = "MultiInputPolicy"
        policy_kwargs = dict(
            features_extractor_class=GraphGPSExtractor,
            features_extractor_kwargs=dict(
                features_dim=args.gps_dim,
                num_layers=args.gps_layers,
                heads=args.gps_heads,
                fixed_k=args.gps_fixed_k,
                dropout=0.1,
                attn_dropout=0.1,
                use_amp=bool(args.amp),
                use_cudagraphs=bool(args.cuda_graphs),
            ),
            # small MLP heads after extractor
            net_arch=dict(pi=[args.gps_head], vf=[args.gps_head]),
            share_features_extractor=True,
        )

    # ---------- Build / load ----------
    if args.model_path:
        model = PPO.load(
            args.model_path,
            env=env,
            n_steps=args.num_steps,
            verbose=1,
            device=device_str,
            tensorboard_log=str(save_dir),
            batch_size=args.batch_size,
            learning_rate=args.learning_rate,
            policy_kwargs=policy_kwargs,
        )
    else:
        model = PPO(
            policy_id,
            env,
            n_steps=args.num_steps,
            verbose=1,
            device=device_str,
            tensorboard_log=str(save_dir),
            batch_size=args.batch_size,
            learning_rate=args.learning_rate,
            clip_range=args.clip_range,
            policy_kwargs=policy_kwargs,
        )
    # --- Force actions to CPU numpy before RolloutBuffer.add ---------------------

    _rb = model.rollout_buffer
    _orig_add = _rb.add

    def _safe_add(obs, action, reward, episode_start, value, log_prob):
        # Some forks return a torch.Tensor action on CUDA; RolloutBuffer expects numpy
        if isinstance(action, torch.Tensor):
            action = action.detach().cpu().numpy()
        return _orig_add(obs, action, reward, episode_start, value, log_prob)

    _rb.add = _safe_add
    # ----------------------------------------------------------------------------

    # Optional: flip extractor flag too
    if args.policy_type == "GraphGPS" and torch.cuda.is_available():
        try:
            model.policy.features_extractor._use_cudagraphs = bool(args.cuda_graphs)
        except Exception:
            pass

    # ---------- Train ----------
    model.learn(total_timesteps=args.num_timesteps, callback=callback)
    model.save(Path(save_dir, "ppo_matsim"))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="Train a PPO model on the MatsimGraphEnv.",
        formatter_class=argparse.ArgumentDefaultsHelpFormatter,
    )
    parser.add_argument("matsim_config", type=str, help="Path to the matsim config.xml file.")
    
    parser.add_argument("--device", type=str, default="auto", choices=["auto", "cpu", "cuda"],
                        help="Device override. 'auto' chooses cuda if available else cpu.")

    parser.add_argument("--seed", type=int, default=42, help="Global RNG seed propagated to PyTorch, Gym, and MATSim.")
    parser.add_argument("--num_timesteps", type=int, default=1_000_000,
                        help="Total timesteps: n_steps * num_envs * iterations.")
    parser.add_argument("--num_envs", type=int, default=100, help="Number of parallel envs.")
    parser.add_argument("--num_agents", type=int, default=-1,
                        help="If < 0, use existing plans/vehicles; otherwise regenerate.")
    parser.add_argument("--mlp_dims", default="256 128 64",
                        help="MLP hidden sizes (space-separated).")
    parser.add_argument("--results_dir", type=str, default=Path(Path(__file__).parent, "ppo_results"),
                        help="Directory for TB logs and checkpoints.")
    parser.add_argument("--num_steps", type=int, default=1,
                        help="On-policy rollout horizon (per env).")
    parser.add_argument("--batch_size", type=int, default=25,
                        help="PPO minibatch size.")
    parser.add_argument("--learning_rate", type=float, default=1e-5,
                        help="Optimizer LR.")
    parser.add_argument("--model_path", default=None,
                        help="Resume from model.zip.")
    parser.add_argument("--save_frequency", type=int, default=10_000,
                        help="Checkpoint frequency in *total* timesteps.")
    parser.add_argument("--clip_range", type=float, default=0.2, help="PPO clip range.")
    parser.add_argument("--policy_type", default="MlpPolicy",
                        choices=["MlpPolicy", "GNNPolicy", "GraphGPS"],
                        type=str, help="Policy / encoder backbone.")
    parser.add_argument(
        "--backend",
        type=str,
        default=os.getenv("RLEV_BACKEND", "python"),
        choices=["python", "server", "jvm"],
        help="Where to run MATSim from the env: 'python' (local JPype), 'jvm' (local via CLI JPype), or 'server' (HTTP).",
    )
    parser.add_argument("--fast_opts", action="store_true",
                    help="Pass fastOpts=True into the MatsimGraphEnv (JPype runner).")
    # GraphGPS knobs
    parser.add_argument("--gps_dim", type=int, default=128, help="GraphGPS features_dim.")
    parser.add_argument("--gps_layers", type=int, default=3, help="GraphGPS number of layers.")
    parser.add_argument("--gps_heads", type=int, default=4, help="GraphGPS attention heads.")
    parser.add_argument("--gps_fixed_k", type=int, default=128, help="Fixed K (node cap) for padding/bucketing.")
    parser.add_argument("--gps_head", type=int, default=128, help="Size of policy/value MLP heads.")
    parser.add_argument("--amp", action="store_true", help="Enable mixed precision in extractor.")
    parser.add_argument("--cuda_graphs", action="store_true", help="Enable CUDA Graphs in extractor.")

    args = parser.parse_args()
    args.mlp_dims = [int(x) for x in str(args.mlp_dims).split()]
    _seed_everything(args.seed)
    main(args)
