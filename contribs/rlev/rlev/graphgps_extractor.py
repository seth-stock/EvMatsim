import torch
import torch.nn as nn
from typing import Dict, Any, Optional
from stable_baselines3.common.torch_layers import BaseFeaturesExtractor

from torch_geometric.data import Data
from torch_geometric.nn import GINConv, global_mean_pool


def _to_device(arr, device: torch.device):
    return torch.as_tensor(arr, device=device)


def _build_fixed_subgraph(edge_index_2E: torch.Tensor, K: int, N: int) -> tuple[torch.Tensor, torch.Tensor]:
    # degree-based fixed node subset (capture-safe; no host sync)
    deg = torch.bincount(edge_index_2E.view(-1), minlength=N)
    K = min(K, N)
    sel_idx = torch.topk(deg, k=K, largest=True, sorted=True).indices  # (K,)
    mask = torch.zeros(N, dtype=torch.bool, device=edge_index_2E.device); mask[sel_idx] = True
    u, v = edge_index_2E[0], edge_index_2E[1]
    keep = mask[u] & mask[v]
    ei = edge_index_2E[:, keep]  # (2, E_sel) in original ids
    remap = -torch.ones(N, dtype=torch.long, device=edge_index_2E.device)
    remap[sel_idx] = torch.arange(sel_idx.numel(), device=edge_index_2E.device)
    ei_sel = remap[ei]  # reindexed to [0..K-1]
    return sel_idx, ei_sel


class LocalGlobalBlock(nn.Module):
    def __init__(self, dim: int, heads: int, dropout: float, attn_dropout: float):
        super().__init__()
        mlp = nn.Sequential(nn.Linear(dim, dim), nn.ReLU(), nn.Linear(dim, dim))
        self.local = GINConv(nn=mlp, train_eps=False)
        self.attn = nn.MultiheadAttention(dim, heads, dropout=attn_dropout, batch_first=True)
        self.drop = nn.Dropout(dropout)
        self.norm1 = nn.LayerNorm(dim)
        self.norm2 = nn.LayerNorm(dim)

    def forward(self, x_flat: torch.Tensor, edge_index: torch.Tensor, B: int, K: int) -> torch.Tensor:
        # x_flat: (B*K, D)
        dtype = x_flat.dtype

        # Run LayerNorm in fp32 for numerical stability, then project back
        x_norm = self.norm1(x_flat.to(torch.float32)).to(dtype)

        # Local message passing (always eval in fp32 to avoid half precision NaNs)
        local_in = x_norm.to(torch.float32)
        h = self.local(local_in, edge_index).to(dtype)  # (B*K, D)
        h = torch.nan_to_num(h, nan=0.0, posinf=1e4, neginf=-1e4)
        x = x_norm + self.drop(h)                       # residual after local
        x = torch.nan_to_num(x, nan=0.0, posinf=1e4, neginf=-1e4)

        # Global attention: compute scores in fp32 to avoid softmax overflow
        tokens = torch.nan_to_num(x.view(B, K, -1), nan=0.0, posinf=1e4, neginf=-1e4)
        attn_in = tokens.to(torch.float32)
        attn_out, _ = self.attn(attn_in, attn_in, attn_in, need_weights=False)
        attn_out = torch.nan_to_num(attn_out, nan=0.0, posinf=1e4, neginf=-1e4)
        y = attn_out.to(dtype).reshape(B * K, -1)

        # Second residual + LayerNorm (again keep fp32 inside the norm)
        x = x + self.drop(y)
        x = torch.nan_to_num(x, nan=0.0, posinf=1e4, neginf=-1e4)
        x = self.norm2(x.to(torch.float32)).to(dtype)

        # Clamp any remaining NaNs/Infs that could arise from fp16 overflows
        return torch.nan_to_num(x, nan=0.0, posinf=1e4, neginf=-1e4)


class GraphGPSExtractor(BaseFeaturesExtractor):
    """
    Capture-safe GraphGPS-style extractor:
      - fixed K nodes per graph (constant shapes),
      - local GIN on (B*K,D) with batched edges,
      - global MHA on (B,K,D) without to_dense_batch,
      - mixed precision + CUDA Graphs (optional).
    """
    def __init__(
        self,
        observation_space,
        features_dim: int = 128,
        num_layers: int = 3,
        heads: int = 4,
        dropout: float = 0.1,
        attn_dropout: float = 0.2,
        fixed_k: int = 128,
        use_amp: bool = True,
        use_cudagraphs: bool = True,
    ):
        super().__init__(observation_space, features_dim)
        self.embed_dim = features_dim
        self.fixed_k = int(fixed_k)
        self.use_amp = bool(use_amp)
        self._want_cudagraphs = bool(use_cudagraphs)
        self._amp_warned = False
        self._amp_train_warned = False

        in_dim = int(observation_space["nodes"].shape[-1])  # type: ignore[attr-defined]
        self.in_proj = nn.Identity() if in_dim == self.embed_dim else nn.Linear(in_dim, self.embed_dim)
        self.blocks = nn.ModuleList([
            LocalGlobalBlock(self.embed_dim, heads, dropout, attn_dropout)
            for _ in range(num_layers)
        ])
        self.out_norm = nn.LayerNorm(self.embed_dim)

        # Templates & capture state
        self._sel_idx: Optional[torch.Tensor] = None      # (K,)
        self._ei_single: Optional[torch.Tensor] = None    # (2, E_sel)
        self._batched_ei: Optional[torch.Tensor] = None   # (2, B*E_sel)
        self._batch_vec: Optional[torch.Tensor] = None    # (B*K,)

        self._static_nodes: Optional[torch.Tensor] = None # (B, K, F)
        self._static_out: Optional[torch.Tensor] = None   # (B, D)

        self._graph: Optional[torch.cuda.CUDAGraph] = None
        self._capture_stream: Optional[torch.cuda.Stream] = None
        self._captured: bool = False
        self._B: Optional[int] = None
        self._F: Optional[int] = None

    def _should_use_amp(self, device: torch.device) -> bool:
        """
        Only enable autocast on CUDA when gradients are disabled (i.e., rollout/inference).
        During training (grad enabled) we stay in fp32 to avoid NaNs without a GradScaler.
        """
        return (
            self.use_amp
            and device.type == "cuda"
            and not torch.is_grad_enabled()
        )

    # ---------- fixed templates ----------
    def _ensure_templates(self, obs: Dict[str, Any], device: torch.device):
        x = _to_device(obs["nodes"], device)       # (B,N,F) or (N,F)
        ei = _to_device(obs["edge_index"], device) # (B,2,E) or (2,E)

        if x.dim() == 2:
            B, N, F = 1, x.size(0), x.size(1); ei0 = ei
        else:
            B, N, F = x.size(0), x.size(1), x.size(2); ei0 = ei[0]

        if self._sel_idx is not None and self._B == B and self._F == F:
            return

        sel_idx, ei_sel = _build_fixed_subgraph(ei0.long(), self.fixed_k, N)  # (K,), (2,E_sel)
        K = sel_idx.numel()
        self._sel_idx, self._ei_single = sel_idx, ei_sel

        E_sel = ei_sel.size(1)
        offsets = (torch.arange(B, device=device) * K).view(1, B, 1)    # (1,B,1)
        ei_b = ei_sel.unsqueeze(1).expand(2, B, E_sel) + offsets        # (2,B,E_sel)
        self._batched_ei = ei_b.reshape(2, B * E_sel).contiguous()      # (2, B*E_sel)
        self._batch_vec = torch.repeat_interleave(torch.arange(B, device=device), K)  # (B*K,)

        amp_active = self._should_use_amp(device)
        dtype_nodes = torch.float16 if amp_active else torch.float32

        needs_nodes = (
            self._static_nodes is None
            or self._static_nodes.shape != (B, K, F)
            or self._static_nodes.dtype != dtype_nodes
            or self._static_nodes.device != device
        )
        if needs_nodes:
            self._static_nodes = torch.empty((B, K, F), dtype=dtype_nodes, device=device)
            self._captured = False  # dtype/device change invalidates captured graphs

        needs_out = (
            self._static_out is None
            or self._static_out.shape != (B, self.embed_dim)
            or self._static_out.device != device
        )
        if needs_out:
            self._static_out = torch.empty((B, self.embed_dim), dtype=torch.float32, device=device)
            self._captured = False

        self._B, self._F = B, F
        self._amp_active_cache = amp_active

    def _assemble_data_from_static(self) -> Data:
        x_flat = self._static_nodes.view(self._B * self._sel_idx.numel(), self._F)  # type: ignore[arg-type]
        return Data(x=x_flat, edge_index=self._batched_ei, batch=self._batch_vec)

    # ---------- forward (no capture) ----------
    def _stack_forward(self, data: Data, *, check_amp: bool = True) -> torch.Tensor:
        amp_enabled = self._should_use_amp(data.x.device)
        if self.use_amp and not amp_enabled and torch.is_grad_enabled() and not self._amp_train_warned:
            print("[GraphGPSExtractor] AMP is only used during rollout; training runs in fp32 for stability.", flush=True)
            self._amp_train_warned = True

        def _forward_once():
            x = self.in_proj(data.x.to(self.in_proj.weight.dtype))  # (B*K, D)
            B = self._B
            K = self._sel_idx.numel()

            for block in self.blocks:
                x = block(x, data.edge_index, B=B, K=K)  # (B*K, D)

            g = x.view(B, K, -1).mean(dim=1).contiguous()  # (B, D)
            return self.out_norm(g)

        with torch.amp.autocast(device_type="cuda", enabled=amp_enabled):
            g = _forward_once()

        g_float = g.float()
        if check_amp and amp_enabled and not torch.isfinite(g_float).all():
            with torch.amp.autocast(device_type="cuda", enabled=False):
                g = _forward_once()
            g_float = g.float()
            if not self._amp_warned:
                print("[GraphGPSExtractor] AMP produced NaNs; falling back to fp32.", flush=True)
                self._amp_warned = True
            self.use_amp = False

        g_float = torch.nan_to_num(g_float, nan=0.0, posinf=1e4, neginf=-1e4)
        return g_float


    # ---------- capture ----------
    @torch.no_grad()
    def _maybe_capture(self, obs: Dict[str, Any], device: torch.device):
        if not self._want_cudagraphs or self._captured or device.type != "cuda":
            return

        self._ensure_templates(obs, device)

        # Warmup on default stream
        x = _to_device(obs["nodes"], device)
        if x.dim() == 2: x = x.unsqueeze(0)  # (1,N,F)
        sel = self._sel_idx
        self._static_nodes.copy_(x[:, sel, :].to(self._static_nodes.dtype))
        _ = self._stack_forward(self._assemble_data_from_static())
        torch.cuda.synchronize(device)

        # Capture on a non-default stream
        self._capture_stream = torch.cuda.Stream(device=device)
        g = torch.cuda.CUDAGraph()
        torch.cuda.current_stream(device).wait_stream(self._capture_stream)
        with torch.cuda.stream(self._capture_stream):
            with torch.cuda.graph(g):
                out = self._stack_forward(self._assemble_data_from_static(), check_amp=False)
                self._static_out.copy_(out)
        torch.cuda.current_stream(device).wait_stream(self._capture_stream)

        self._graph = g
        self._captured = True

    # ---------- public forward ----------
    def forward(self, obs: Dict[str, Any]) -> torch.Tensor:
        dev = next(self.parameters()).device
        self._ensure_templates(obs, dev)

        x = _to_device(obs["nodes"], dev)
        if x.dim() == 2: x = x.unsqueeze(0)  # (1,N,F)
        sel = self._sel_idx
        self._static_nodes.copy_(x[:, sel, :].to(self._static_nodes.dtype))

        if self._want_cudagraphs and dev.type == "cuda" and not self._captured:
            self._maybe_capture(obs, dev)

        if self._want_cudagraphs and self._captured and self._graph is not None:
            self._graph.replay()
            return self._static_out
        else:
            return self._stack_forward(self._assemble_data_from_static(), check_amp=True)

