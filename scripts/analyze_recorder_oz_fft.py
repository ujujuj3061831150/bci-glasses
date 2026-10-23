from __future__ import annotations

import argparse
import csv
import re
from pathlib import Path

import matplotlib.pyplot as plt
import mne
import numpy as np
from scipy.signal import butter, detrend, sosfiltfilt


CONDITIONS = (("new8hz", "新8hz", 8.0), ("new10hz", "新10hz", 10.0), ("new12hz", "新12hz", 12.0), ("new15hz", "新15hz", 15.0))


def configure_plots() -> None:
    plt.rcParams.update(
        {
            "font.sans-serif": ["Microsoft YaHei", "SimHei", "Arial"],
            "axes.unicode_minus": False,
            "figure.dpi": 140,
            "savefig.dpi": 220,
            "axes.grid": True,
            "grid.alpha": 0.22,
        }
    )


def segment_sort_key(path: Path) -> int:
    match = re.fullmatch(r"data(?:\.(\d+))?\.bdf", path.name, re.IGNORECASE)
    return int(match.group(1) or 0) if match else 99_999


def load_oz(folder: Path) -> tuple[float, np.ndarray, list[Path]]:
    paths = sorted(folder.glob("data*.bdf"), key=segment_sort_key)
    if not paths:
        raise FileNotFoundError(f"No data*.bdf files in {folder}")

    pieces: list[np.ndarray] = []
    sample_rate: float | None = None
    used_paths: list[Path] = []
    for path in paths:
        try:
            raw = mne.io.read_raw_bdf(path, preload=True, verbose="ERROR")
        except (IndexError, ValueError):
            # Recorder occasionally leaves an empty trailing data.N.bdf file.
            continue
        if "Oz" not in raw.ch_names:
            raise ValueError(f"{path} does not contain Oz")
        fs = float(raw.info["sfreq"])
        if sample_rate is not None and fs != sample_rate:
            raise ValueError(f"Sample-rate mismatch in {path}")
        signal_uv = raw.get_data(picks=["Oz"])[0] * 1e6
        if signal_uv.size:
            pieces.append(signal_uv)
            used_paths.append(path)
            sample_rate = fs

    if sample_rate is None or not pieces:
        raise ValueError(f"No readable Oz samples in {folder}")
    return sample_rate, np.concatenate(pieces), used_paths


def fft_amplitude(signal_uv: np.ndarray, sample_rate: float) -> tuple[np.ndarray, np.ndarray]:
    window = np.hanning(signal_uv.size)
    values = np.fft.rfft(signal_uv * window)
    frequencies = np.fft.rfftfreq(signal_uv.size, d=1.0 / sample_rate)
    amplitude = 2.0 * np.abs(values) / (signal_uv.size * window.mean())
    return frequencies, amplitude


def target_metrics(frequencies: np.ndarray, amplitude: np.ndarray, target_hz: float) -> dict[str, float | str]:
    target_bin = int(np.argmin(np.abs(frequencies - target_hz)))
    local_mask = np.abs(frequencies - target_hz) <= 0.5
    local_indices = np.flatnonzero(local_mask)
    peak_index = int(local_indices[np.argmax(amplitude[local_indices])])
    noise_mask = (
        (np.abs(frequencies - target_hz) <= 2.0)
        & (np.abs(frequencies - target_hz) >= 0.5)
    )
    noise = float(np.median(amplitude[noise_mask]))
    peak = float(amplitude[peak_index])
    snr_db = 20.0 * np.log10(peak / max(noise, 1e-12))
    return {
        "target_bin_hz": float(frequencies[target_bin]),
        "target_bin_amplitude_uv": float(amplitude[target_bin]),
        "local_peak_hz": float(frequencies[peak_index]),
        "local_peak_amplitude_uv": peak,
        "local_peak_snr_db": float(snr_db),
        "peak_check": "yes" if snr_db >= 3.0 else "no",
    }


def plot_four_conditions(
    spectra: dict[str, tuple[np.ndarray, np.ndarray, float]],
    output: Path,
    title: str,
    half_width: float | None,
) -> None:
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), constrained_layout=True)
    colors = ("#2478C5", "#00A087", "#E18727", "#C43C39")
    for axis, (name, _, target), color in zip(axes.flat, CONDITIONS, colors, strict=True):
        frequencies, amplitude, _ = spectra[name]
        if half_width is None:
            mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        else:
            mask = np.abs(frequencies - target) <= half_width
        axis.plot(frequencies[mask], amplitude[mask], color=color, linewidth=1.1)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.0, label=f"target {target:g} Hz")
        axis.set_title(f"Recorded {target:g} Hz")
        axis.set_xlabel("Frequency (Hz)")
        axis.set_ylabel("FFT amplitude (uV)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle(title, fontsize=14)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="Inspect Oz FFT magnitudes in Recorder BDF data")
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    configure_plots()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    raw_spectra: dict[str, tuple[np.ndarray, np.ndarray, float]] = {}
    filtered_spectra: dict[str, tuple[np.ndarray, np.ndarray, float]] = {}
    rows: list[dict[str, float | str]] = []

    for name, folder_name, target in CONDITIONS:
        sample_rate, oz_uv, paths = load_oz(args.input_root / folder_name)
        trim = int(2.0 * sample_rate) if oz_uv.size > int(10.0 * sample_rate) else 0
        signal = oz_uv[trim:-trim] if trim else oz_uv
        detrended = detrend(signal, type="linear")
        sos = butter(4, [1.0, 40.0], btype="bandpass", fs=sample_rate, output="sos")
        filtered = sosfiltfilt(sos, detrended)
        raw_frequencies, raw_amplitude = fft_amplitude(detrended, sample_rate)
        filtered_frequencies, filtered_amplitude = fft_amplitude(filtered, sample_rate)
        raw_spectra[name] = (raw_frequencies, raw_amplitude, target)
        filtered_spectra[name] = (filtered_frequencies, filtered_amplitude, target)
        metrics = target_metrics(filtered_frequencies, filtered_amplitude, target)
        rows.append(
            {
                "recording_folder": folder_name,
                "target_hz": target,
                "sample_rate_hz": sample_rate,
                "duration_after_trim_s": signal.size / sample_rate,
                "bdf_segments": "; ".join(path.name for path in paths),
                **metrics,
            }
        )

    plot_four_conditions(raw_spectra, args.output_dir / "01_oz_detrended_raw_fft.png", "Oz detrended raw BDF FFT magnitude", None)
    plot_four_conditions(filtered_spectra, args.output_dir / "02_oz_filtered_fft.png", "Oz 1-40 Hz filtered FFT magnitude", None)
    plot_four_conditions(filtered_spectra, args.output_dir / "03_oz_target_band_fft.png", "Oz filtered FFT magnitude near each target", 2.0)

    with (args.output_dir / "04_oz_fft_metrics.csv").open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary_lines = [
        "Recorder BDF Oz FFT magnitude check",
        f"Input root: {args.input_root}",
        "Method: concatenate data*.bdf, trim first/last 2 s, detrend, then compare raw and 1-40 Hz zero-phase filtered FFT magnitudes.",
        "Peak check: filtered target-neighborhood peak SNR >= 3 dB relative to the surrounding 0.5-2 Hz amplitude median.",
        "No valid Recorder/App stimulus event markers are available here; folder labels are only recording labels, not precise trial alignment.",
        "",
    ]
    for row in rows:
        summary_lines.append(
            f"{row['target_hz']:g} Hz: local peak {row['local_peak_hz']:.3f} Hz, "
            f"amplitude {row['local_peak_amplitude_uv']:.3f} uV, "
            f"SNR {row['local_peak_snr_db']:.2f} dB, peak={row['peak_check']}"
        )
    (args.output_dir / "00_summary.txt").write_text("\n".join(summary_lines) + "\n", encoding="utf-8")
    print((args.output_dir / "00_summary.txt").read_text(encoding="utf-8"))


if __name__ == "__main__":
    main()
