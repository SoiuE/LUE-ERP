from __future__ import annotations

from io import BytesIO

import numpy as np
from PIL import Image


class OpenClipEmbedder:
    def __init__(self, model_name: str, pretrained: str):
        import open_clip
        import torch

        self._torch = torch
        self.model, _, self.preprocess = open_clip.create_model_and_transforms(model_name, pretrained=pretrained, device="cpu")
        self.model.eval()
        self.version = f"openclip:{model_name}:{pretrained}"

    def embed(self, content: bytes) -> np.ndarray:
        image = Image.open(BytesIO(content)).convert("RGB")
        tensor = self.preprocess(image).unsqueeze(0)
        with self._torch.inference_mode():
            vector = self.model.encode_image(tensor).float().cpu().numpy()[0]
        return vector.astype(np.float32)
