# rlev/envs/matsim_graph_env_gps.py
import numpy as np
import gymnasium as gym
from gymnasium import spaces
import torch
from typing import Dict, Any

from .matsim_graph_env import MatsimGraphEnv


class MatsimGraphEnvGPS(MatsimGraphEnv):
    """
    GraphGPS-friendly env:
      - observation is a Dict with:
          'nodes'      : (N, F) float32   -> node features from linegraph.x
          'edge_index' : (2, E) int64     -> COO indices of the line graph
      - actions are identical to MatsimGraphEnv (MultiDiscrete over links)
    """

    def __init__(self, config_path, num_agents=100, save_dir=None, backend: str = "python", fast_opts: bool = False, seed: int | None = None):
        super().__init__(config_path, num_agents=num_agents, save_dir=save_dir, backend=backend, fast_opts=fast_opts, seed=seed)

        # Shapes from the prepared dataset
        node_feat = self.dataset.linegraph.x           # torch.Tensor (N, F)
        edge_index = self.dataset.linegraph.edge_index # torch.LongTensor (2, E)

        # Cache sizes
        self._N = int(node_feat.shape[0])
        self._F = int(node_feat.shape[1])
        self._E = int(edge_index.shape[1])

        # Build observation space (MANDATORY for Gym)
        # nodes in [0,1] because your dataset.x is normalized that way
        self.observation_space = spaces.Dict({
            "nodes": spaces.Box(low=0.0, high=1.0,
                                shape=(self._N, self._F), dtype=np.float32),
            # edge_index is fixed; we expose as int64 Box with min/max bounds
            "edge_index": spaces.Box(low=0, high=max(self._N - 1, 1),
                                     shape=(2, self._E), dtype=np.int64),
        })

        # action_space is already created in the base class from num_links
        # (MultiDiscrete with length equal to number of linegraph nodes)

        # internal bookkeeping
        self._episode_steps = 0
        self.done = False
        self.reward = 0.0

        # cache edge_index numpy once (constant for the scenario)
        self._edge_index_np = edge_index.cpu().numpy().astype(np.int64)

    # ---------- helpers -----------------------------------------------------

    def _current_obs(self) -> Dict[str, Any]:
        # nodes as float32 numpy
        nodes_np = self.dataset.linegraph.x.cpu().numpy().astype(np.float32)
        return {
            "nodes": nodes_np,
            "edge_index": self._edge_index_np,
        }

    # ---------- Gym API -----------------------------------------------------

    def reset(self, *, seed=None, options=None):
        self._maybe_update_seed(seed)
        self.done = False
        self.reward = 0.0
        self._episode_steps = 0
        obs = self._current_obs()
        info: Dict[str, Any] = {}
        return obs, info

    def step(self, actions: np.ndarray):
        """
        actions: shape (num_links,) with integer choices in [0, num_charger_types-1]
        """
        # Sanitize
        actions = np.asarray(actions, dtype=np.int64)
        if actions.ndim != 1:
            actions = actions.reshape(-1)
        actions = np.clip(actions, 0, self.num_charger_types - 1)

        # Compute reward via the server round-trip
        r = self.send_reward_request(actions)

        self.reward = float(r)
        self._episode_steps += 1

        # One-step episodes (PPO will collect n_steps rollouts anyway)
        terminated = False
        truncated = False

        info = {"graph_env_inst": self}
        return self._current_obs(), self.reward, terminated, truncated, info
