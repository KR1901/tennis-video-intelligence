#!/usr/bin/env python3
"""Prepare the licensed on-device pose and tennis-ball models for the Android APK.

The binary assets are created during CI/local build and are intentionally not
committed to Git. TrackNet V1 weights are hosted by Hugging Face under MIT;
the Pose Landmarker task is downloaded from the official MediaPipe model publisher.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
from pathlib import Path


POSE_MODEL_URL = (
    "https://huggingface.co/litert-community/MediaPipe-PoseLandmarker-Lite/"
    "resolve/main/pose_landmarker_lite.task"
)
POSE_MODEL_SHA256 = "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
TRACKNET_WEIGHTS_URL = (
    "https://huggingface.co/vishnushenoy09/tracknet-v1-tennis/"
    "resolve/main/tracknet_weights.pth?download=true"
)
POSE_MODEL_NAME = "pose_landmarker_lite.task"
TRACKNET_MODEL_NAME = "tracknet_v1.onnx"


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def download(url: str, path: Path, min_size: int, max_size: int) -> dict[str, object]:
    path.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "TennisVideoIntelligence-model-prep/1.0"},
    )
    temporary = path.with_suffix(path.suffix + ".download")
    try:
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open("wb") as output:
            final_url = response.geturl()
            if not final_url.startswith("https://"):
                raise RuntimeError(f"Model source did not remain HTTPS: {final_url}")
            total = 0
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_size:
                    raise RuntimeError(f"Model exceeded {max_size} bytes")
                output.write(chunk)
        if total < min_size:
            raise RuntimeError(f"Downloaded model was unexpectedly small ({total} bytes)")
        temporary.replace(path)
    except Exception:
        temporary.unlink(missing_ok=True)
        raise
    return {
        "url": url,
        "size_bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def make_tracknet_onnx(weights_path: Path, output_path: Path) -> dict[str, object]:
    try:
        import onnx
        import torch
        from torch import nn
    except ImportError as exc:
        raise RuntimeError(
            "Model conversion needs torch and onnx. Install scripts/model-requirements.txt first."
        ) from exc

    class ConvBlock(nn.Module):
        def __init__(self, input_channels: int, output_channels: int) -> None:
            super().__init__()
            self.block = nn.Sequential(
                nn.Conv2d(input_channels, output_channels, 3, padding=1, bias=True),
                nn.ReLU(),
                nn.BatchNorm2d(output_channels),
            )

        def forward(self, value):
            return self.block(value)

    class BallTrackerNet(nn.Module):
        # Module names mirror the public TrackNet V1 checkpoint tensor keys.
        def __init__(self, out_channels: int = 256) -> None:
            super().__init__()
            self.out_channels = out_channels
            self.conv1 = ConvBlock(9, 64)
            self.conv2 = ConvBlock(64, 64)
            self.pool1 = nn.MaxPool2d(2, 2)
            self.conv3 = ConvBlock(64, 128)
            self.conv4 = ConvBlock(128, 128)
            self.pool2 = nn.MaxPool2d(2, 2)
            self.conv5 = ConvBlock(128, 256)
            self.conv6 = ConvBlock(256, 256)
            self.conv7 = ConvBlock(256, 256)
            self.pool3 = nn.MaxPool2d(2, 2)
            self.conv8 = ConvBlock(256, 512)
            self.conv9 = ConvBlock(512, 512)
            self.conv10 = ConvBlock(512, 512)
            self.ups1 = nn.Upsample(scale_factor=2)
            self.conv11 = ConvBlock(512, 256)
            self.conv12 = ConvBlock(256, 256)
            self.conv13 = ConvBlock(256, 256)
            self.ups2 = nn.Upsample(scale_factor=2)
            self.conv14 = ConvBlock(256, 128)
            self.conv15 = ConvBlock(128, 128)
            self.ups3 = nn.Upsample(scale_factor=2)
            self.conv16 = ConvBlock(128, 64)
            self.conv17 = ConvBlock(64, 64)
            self.conv18 = ConvBlock(64, out_channels)

        def forward(self, value):
            batch_size = value.size(0)
            value = self.conv2(self.conv1(value))
            value = self.pool1(value)
            value = self.conv4(self.conv3(value))
            value = self.pool2(value)
            value = self.conv7(self.conv6(self.conv5(value)))
            value = self.pool3(value)
            value = self.conv10(self.conv9(self.conv8(value)))
            value = self.ups1(value)
            value = self.conv13(self.conv12(self.conv11(value)))
            value = self.ups2(value)
            value = self.conv15(self.conv14(value))
            value = self.ups3(value)
            value = self.conv18(self.conv17(self.conv16(value)))
            return value.reshape(batch_size, self.out_channels, -1)

    class HeatmapOutput(nn.Module):
        def __init__(self, network: nn.Module) -> None:
            super().__init__()
            self.network = network

        def forward(self, frames):
            logits = self.network(frames).reshape(-1, 256, 360, 640)
            # TrackNet class 255 represents the tennis-ball mask. Export its calibrated
            # per-pixel softmax probability rather than the argmax class identifier.
            return torch.softmax(logits, dim=1)[:, 255, :, :]

    torch.set_num_threads(2)
    state = torch.load(weights_path, map_location="cpu", weights_only=True)
    if isinstance(state, dict) and "state_dict" in state:
        state = state["state_dict"]
    if isinstance(state, dict) and "model_state_dict" in state:
        state = state["model_state_dict"]
    if not isinstance(state, dict):
        raise RuntimeError("TrackNet checkpoint did not contain a model state dictionary")

    model = BallTrackerNet(out_channels=256)
    model.load_state_dict(state, strict=True)
    model.eval()
    export_model = HeatmapOutput(model).eval()
    dummy = torch.zeros((1, 9, 360, 640), dtype=torch.float32)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.onnx.export(
        export_model,
        dummy,
        str(output_path),
        input_names=["three_rgb_frames_bgr_newest_first"],
        output_names=["ball_class_heatmap"],
        opset_version=17,
        do_constant_folding=True,
        dynamic_axes=None,
    )
    onnx_model = onnx.load(str(output_path))
    onnx.checker.check_model(onnx_model)
    return {
        "source": "vishnushenoy09/tracknet-v1-tennis",
        "source_license": "MIT",
        "input_shape": [1, 9, 360, 640],
        "output_shape": [1, 360, 640],
        "output_encoding": "float32 softmax probability for TrackNet class 255 (tennis ball), 0..1",
        "size_bytes": output_path.stat().st_size,
        "sha256": sha256(output_path),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("android/app/src/main/assets/models"),
        help="Android model assets directory",
    )
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)

    pose_path = args.output / POSE_MODEL_NAME
    tracknet_path = args.output / TRACKNET_MODEL_NAME
    pose_info = download(POSE_MODEL_URL, pose_path, 1_000_000, 30_000_000)
    if pose_info["sha256"] != POSE_MODEL_SHA256:
        raise RuntimeError(
            "Pose Landmarker checksum mismatch: expected "
            + POSE_MODEL_SHA256
            + ", got "
            + str(pose_info["sha256"])
        )

    weights_path = args.output / "tracknet_weights.pth"
    tracknet_info = download(TRACKNET_WEIGHTS_URL, weights_path, 1_000_000, 250_000_000)
    tracknet_info = make_tracknet_onnx(weights_path, tracknet_path)
    weights_path.unlink(missing_ok=True)

    manifest = {
        "generated_by": "scripts/prepare_android_models.py",
        "pose_landmarker_lite": pose_info,
        "tennis_ball_tracknet_v1": tracknet_info,
    }
    (args.output / "model-manifest.json").write_text(
        json.dumps(manifest, indent=2) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
