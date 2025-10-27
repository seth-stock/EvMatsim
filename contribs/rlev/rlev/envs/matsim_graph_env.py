# rlev/envs/matsim_graph_env.py
import gymnasium as gym
import numpy as np
import shutil
import torch
import requests
import json
import zipfile
import pandas as pd
import os
import re
import math
from abc import abstractmethod
from gymnasium import spaces
from rlev.classes.matsim_xml_dataset import MatsimXMLDataset
from pathlib import Path
from rlev.classes.chargers import Charger, StaticCharger, NoneCharger, DynamicCharger
from typing import List
from filelock import FileLock
from rlev.scripts.create_chargers import create_chargers_xml_gymnasium

# Local JVM runner (must exist at rlev/java_jvm.py)
try:
    from rlev.java_jvm import run_controler  # (config_path: Path, output_dir: Path, fast_opts: bool=True) -> tuple[int, str]
except Exception:
    run_controler = None  # guarded when backend="server"
try:
    from rlev import java_jvm
    run_controler = getattr(java_jvm, "run_controler", None)
except Exception as e:
    print(f"[env] import error rlev.java_jvm: {e}", flush=True)
    run_controler = None

class MatsimGraphEnv(gym.Env):
    """
    A custom Gymnasium environment for Matsim graph-based simulations.

    backend:
      - "python"/"jvm": run MATSim locally via JPype helper (no HTTP).
      - "server": old behavior, POST to HTTP reward server.
    """

    def __init__(self, config_path, num_agents=100, save_dir=None, backend: str = "python"):
        super().__init__()
        self.save_dir = save_dir
        self.backend = (backend or "python").lower()
        if self.backend not in {"python", "server", "jvm"}:
            raise ValueError(f"Unsupported backend={backend}. Choose 'python', 'server', or 'jvm'.")

        print(f"[ENV] backend={self.backend}", flush=True)

        from datetime import datetime
        from uuid import uuid4

        current_time = datetime.now()
        self.time_string = current_time.strftime("%Y%m%d_%H%M%S_%f") + "_" + uuid4().hex[:8]
        if num_agents < 0:
            num_agents = None
        self.num_agents = num_agents

        # Initialize dataset
        self.config_path: Path = Path(config_path)
        self.charger_list: List[Charger] = [NoneCharger, DynamicCharger, StaticCharger]
        self.dataset = MatsimXMLDataset(
            self.config_path,
            self.time_string,
            self.charger_list,
            num_agents=self.num_agents,
            initial_soc=0.5,
        )
        self.num_links_reward_scale = -100
        self.reward: float = 0
        self.best_reward = -np.inf
        self.num_charger_types: int = len(self.charger_list)

        # Action/obs spaces
        self.action_space: spaces.MultiDiscrete = spaces.MultiDiscrete(
            [self.num_charger_types] * self.dataset.linegraph.num_nodes
        )
        self.x = spaces.Box(
            low=0,
            high=1,
            shape=self.dataset.linegraph.x.shape,
            dtype=np.float32,
        )
        self.edge_index = self.dataset.linegraph.edge_index.to(torch.int32)
        edge_index_np = self.edge_index.numpy()
        max_edge_index = np.max(edge_index_np) + 1
        self.edge_index_space = spaces.Box(
            low=edge_index_np,
            high=np.full(edge_index_np.shape, max_edge_index),
            shape=self.edge_index.shape,
            dtype=np.int32,
        )
        self.done: bool = False
        self.lock_file = Path(self.save_dir, "lockfile.lock") if self.save_dir else Path("lockfile.lock")
        self.best_output_response = None
        self._charger_efficiency = 0.0
        self._time_efficiency = 0.0
        self._charger_cost = 0.0

        # Only used if backend == "server"
        self.server_url = os.environ.get("RLEV_SERVER_URL", "http://localhost:8000")

    # ------------------------ Utilities (server path) ------------------------

    def save_server_output(self, response, filetype):
        """
        Save server output to a zip file and extract its contents.
        """
        if self.save_dir is None:
            return
        zip_filename = Path(self.save_dir, f"{filetype}.zip")
        extract_folder = Path(self.save_dir, filetype)

        lock = FileLock(self.lock_file)
        with lock:
            with open(zip_filename, "wb") as f:
                f.write(response.content)
            print(f"Saved zip file: {zip_filename}")
            with zipfile.ZipFile(zip_filename, "r") as zip_ref:
                zip_ref.extractall(extract_folder)
            print(f"Extracted files to: {extract_folder}")

    # ------------------------ Reward core (shared) ---------------------------

    def _compute_rewards_from_outputs(self, output_dir: Path, controller_log: str | None = None) -> tuple[float, float]:
        """
        Reads MATSim output files in output_dir and returns (charge_reward, time_reward).
        Mirrors server-side logic.
        """
        charge_reward: float | None = None
        time_reward: float | None = None

        out_dir = Path(output_dir)
        charge_csv = out_dir / "ITERS" / "it.0" / "0.average_charge_time_profiles.txt"
        legdur_txt = out_dir / "ITERS" / "it.0" / "0.legdurations.txt"

        log_blob = controller_log or ""

        # Charge reward (primary: charger profiles)
        if charge_csv.is_file():
            avg_energy_capacity = float(self.dataset.get_average_energy_capacity(self.dataset.vehicle_xml_path))
            avg_charge_integral = 0.0
            tot_records = 0.0
            try:
                with open(charge_csv, "r", encoding="utf-8") as reader:
                    _ = reader.readline()  # header
                    for line in reader:
                        parts = line.strip().split("\t")
                        if len(parts) >= 3:
                            avg_val = float(parts[2])
                            avg_charge_integral += avg_val
                            tot_records += 1.0
            except Exception as exc:
                print(f"[local] Failed reading charge profiles: {exc}")

            tot_energy_capacity = avg_energy_capacity * max(tot_records, 1.0)
            if tot_energy_capacity > 0:
                charge_reward = avg_charge_integral / tot_energy_capacity

        # Fallback: derive a proxy signal from MATSim's reported scores
        if charge_reward is None and log_blob:
            score_match = re.search(r"avg\. score of the executed plan of each agent:\s+(-?[0-9.]+)", log_blob)
            if score_match:
                try:
                    score_val = float(score_match.group(1))
                    charge_reward = math.tanh(score_val / 100.0)
                except ValueError:
                    charge_reward = None

        if charge_reward is None:
            charge_reward = 0.0

        # Time reward (primary: leg duration summary file)
        if legdur_txt.is_file():
            try:
                text = Path(legdur_txt).read_text(encoding="utf-8", errors="ignore")
                dur_match = re.search(r"average leg duration:\s+([0-9.]+)\s+seconds", text)
                if dur_match:
                    seconds = float(dur_match.group(1))
                    time_reward = seconds / 86400.0
            except Exception as exc:
                print(f"[local] Failed reading leg durations: {exc}")

        if time_reward is None and log_blob:
            dur_match = re.search(r"average trip \(probably: leg\) duration is:\s+([0-9.]+) seconds", log_blob)
            if dur_match:
                try:
                    seconds = float(dur_match.group(1))
                    time_reward = seconds / 86400.0
                except ValueError:
                    time_reward = None

        if time_reward is None:
            time_reward = 0.0

        return charge_reward, time_reward
    def _finish_reward_common(self, charge_reward: float, time_reward: float):
        """Computes final reward, updates trackers, returns total reward."""
        self._charger_efficiency = float(charge_reward)
        self._time_efficiency = float(time_reward)

        charger_cost_raw = self.dataset.parse_charger_network_get_charger_cost()
        try:
            charger_cost = float(charger_cost_raw)
        except Exception:
            charger_cost = float(charger_cost_raw.item())
        self._charger_cost = charger_cost

        charger_cost_reward = charger_cost / float(self.dataset.max_charger_cost)
        reward = charge_reward - time_reward - charger_cost_reward

        if reward > self.best_reward:
            self.best_reward = reward

        self._reward = reward
        self.reward = reward
        return reward

    # <<< FIXED: now a proper class method (dedented) >>>
    def send_reward_request(self, actions):
        """
        Entry point used by env.step(): dispatch to local JVM or server path.
        """
        # Always create the chargers XML first (used by both paths)
        create_chargers_xml_gymnasium(
            self.dataset.charger_xml_path,
            self.charger_list,
            actions,
            self.dataset.edge_mapping,
        )

        if self.backend in {"python", "jvm"}:
            return self._run_local_and_reward(actions)
        elif self.backend == "server":
            return self._post_to_server_and_reward(actions)
        else:
            raise RuntimeError(f"Unknown backend '{self.backend}'")

    def _sync_outputs_to_scenario(self, output_dir: Path) -> None:
        """
        Copy MATSim outputs from the sandbox into the scenario's output/ directory.
        """
        scenario_dir = getattr(self.dataset, "original_scenario_dir", None)
        if scenario_dir is None:
            return
        try:
            scenario_dir = Path(scenario_dir)
        except Exception:
            return

        target_root = scenario_dir / "output"
        target_root.mkdir(parents=True, exist_ok=True)

        for item in output_dir.iterdir():
            target = target_root / item.name
            if item.is_dir():
                if target.exists():
                    shutil.rmtree(target, ignore_errors=True)
                shutil.copytree(item, target)
            else:
                shutil.copy2(item, target)

    # --------- Local JVM backend (no HTTP) ---------

    def _run_local_and_reward(self, actions):
        """
        Run MATSim in-process by calling the JVM and compute rewards from the local output.
        """
        # Ensure chargers.xml reflects current actions (already written in send_reward_request)

        # Output directory (isolated per rollout)
        output_dir = self.dataset.config_path.parent / "output"
        output_dir.mkdir(parents=True, exist_ok=True)

        if run_controler is None:
            raise RuntimeError("Local JVM backend requested but run_controler is not available.")

        rc, controller_log = run_controler(self.dataset.config_path, output_dir, fast_opts=True)
        if rc != 0:
            print(f"[local] MATSim Controler.run() returned non-zero code: {rc}")

        # Read outputs and compute rewards
        charge_reward, time_reward = self._compute_rewards_from_outputs(output_dir, controller_log=controller_log)
        reward = self._finish_reward_common(charge_reward, time_reward)
        self._sync_outputs_to_scenario(output_dir)
        return reward

    # --------- HTTP server backend (legacy) ---------

    def _post_to_server_and_reward(self, actions):
        """
        Old behavior: POST to reward server and compute reward from HTTP response.
        """
        response = None

        url = f"{self.server_url}/getReward"
        files = {
            "config": open(self.dataset.config_path, "rb"),
            "network": open(self.dataset.network_xml_path, "rb"),
            "plans": open(self.dataset.plan_xml_path, "rb"),
            "vehicles": open(self.dataset.vehicle_xml_path, "rb"),
            "chargers": open(self.dataset.charger_xml_path, "rb"),
            "counts": open(self.dataset.counts_xml_path, "rb"),
            "consumption_map": open(self.dataset.consumption_map_path, "rb"),
        }
        try:
            response = requests.post(url, params={"folder_name": self.time_string}, files=files, timeout=600)
        finally:
            for f in files.values():
                try:
                    f.close()
                except Exception:
                    pass

        json_response = json.loads(response.headers["X-response-message"])

        filetype = json_response.get("filetype", "none")
        if filetype == "initialoutput" and response.headers.get("Content-Length", "0") != "0":
            self.save_server_output(response, filetype)

        charge_reward = float(json_response["charge_reward"])
        time_reward = float(json_response["time_reward"])

        # keep for TensorBoardCallback "best output" snapshot
        if getattr(self, "_reward", -np.inf) > self.best_reward:
            self.best_output_response = response

        return self._finish_reward_common(charge_reward, time_reward)

    # ------------------------ Gym API ----------------------------------------

    @abstractmethod
    def reset(self, **kwargs):
        pass

    @abstractmethod
    def step(self, actions):
        pass

    def close(self):
        """
        Clean up resources used by the environment.
        """
        try:
            shutil.rmtree(self.dataset.config_path.parent, ignore_errors=True)
        except Exception as e:
            print(f"[env.close] Cleanup warning: {e}")

    def save_charger_config_to_csv(self, csv_path):
        """
        Save the current charger configuration to a CSV file.
        """
        static_chargers = []
        dynamic_chargers = []
        charger_config = self.dataset.graph.edge_attr[:, 3:]

        for idx, row in enumerate(charger_config):
            if not row[0]:
                if row[1]:
                    dynamic_chargers.append(int(self.dataset.edge_mapping.inverse[idx]))
                elif row[2]:
                    static_chargers.append(int(self.dataset.edge_mapping.inverse[idx]))

        df = pd.DataFrame(
            {
                "iteration": [0],
                "reward": [self.reward],
                "cost": [float(self.dataset.charger_cost)],
                "static_chargers": [static_chargers],
                "dynamic_chargers": [dynamic_chargers],
            }
        )
        df.to_csv(csv_path, index=False)

