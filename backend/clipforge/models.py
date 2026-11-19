"""Pydantic models for processing settings and API payloads."""
from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator

AspectRatio = Literal["9:16", "1:1", "16:9"]
Resolution = Literal["1080p", "720p"]
CropPosition = Literal["center", "left", "right", "custom"]
FitMode = Literal["crop", "pad"]
SegmentationMethod = Literal["hybrid", "fixed"]
Encoder = Literal["software", "hardware"]
X264Preset = Literal["ultrafast", "superfast", "veryfast", "faster", "fast",
                     "medium", "slow", "slower", "veryslow"]
ExportFormat = Literal["mp4", "mov"]
ShortRemainder = Literal["drop", "merge"]

# Output pixel sizes. The short side is the resolution label (1080 / 720).
OUTPUT_SIZES: dict[tuple[str, str], tuple[int, int]] = {
    ("9:16", "1080p"): (1080, 1920),
    ("9:16", "720p"): (720, 1280),
    ("1:1", "1080p"): (1080, 1080),
    ("1:1", "720p"): (720, 720),
    ("16:9", "1080p"): (1920, 1080),
    ("16:9", "720p"): (1280, 720),
}


class ProcessingSettings(BaseModel):
    """Every parameter that affects output. Stored verbatim in the manifest."""

    model_config = ConfigDict(extra="forbid")

    # Segmentation
    segmentation_method: SegmentationMethod = "hybrid"
    target_duration: float = Field(30.0, ge=3.0, le=600.0)
    scene_threshold: float = Field(0.30, ge=0.05, le=0.95)
    scene_window: float = Field(5.0, ge=0.0, le=120.0)
    min_duration: float = Field(15.0, ge=1.0, le=600.0)
    max_duration: Optional[float] = Field(None, ge=1.0, le=1200.0,
                                          description="Defaults to target + window")
    remainder_min: float = Field(10.0, ge=0.5, le=600.0)
    short_remainder: ShortRemainder = "drop"
    overlap: float = Field(0.0, ge=0.0, le=30.0)

    # Formatting
    aspect_ratio: AspectRatio = "9:16"
    resolution: Resolution = "1080p"
    fit_mode: FitMode = "crop"
    crop_position: CropPosition = "center"
    crop_offset: float = Field(0.5, ge=0.0, le=1.0,
                               description="Used when crop_position='custom'. 0=left/top, 1=right/bottom")

    # Encoding
    crf: int = Field(20, ge=0, le=51)
    preset: X264Preset = "medium"
    encoder: Encoder = "software"
    export_format: ExportFormat = "mp4"

    # Audio
    audio_bitrate_kbps: int = Field(192, ge=64, le=512)
    audio_normalize: bool = False

    # Outputs
    thumbnails: bool = True

    @model_validator(mode="after")
    def _check_consistency(self) -> "ProcessingSettings":
        if self.min_duration > self.target_duration:
            raise ValueError("min_duration must be <= target_duration")
        if self.max_duration is not None and self.max_duration < self.target_duration:
            raise ValueError("max_duration must be >= target_duration")
        if self.overlap >= self.min_duration:
            raise ValueError("overlap must be smaller than min_duration")
        return self

    @property
    def effective_max_duration(self) -> float:
        if self.max_duration is not None:
            return self.max_duration
        return self.target_duration + self.scene_window

    @property
    def output_size(self) -> tuple[int, int]:
        return OUTPUT_SIZES[(self.aspect_ratio, self.resolution)]

    @property
    def crop_fraction(self) -> float:
        return {"center": 0.5, "left": 0.0, "right": 1.0}.get(self.crop_position, self.crop_offset)


class AppSettings(BaseModel):
    """Persisted user settings: processing defaults plus optional owner info."""

    model_config = ConfigDict(extra="forbid")

    defaults: ProcessingSettings = Field(default_factory=ProcessingSettings)
    owner_name: str = Field("", max_length=200)
    owner_contact: str = Field("", max_length=200)
    include_owner_in_manifest: bool = False


class SessionRequest(BaseModel):
    token: str = Field(..., min_length=1, max_length=512)
