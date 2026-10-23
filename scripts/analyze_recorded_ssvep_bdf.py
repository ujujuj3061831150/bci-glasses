from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import mne
import numpy as np
from scipy.signal import butter, detrend, find_peaks, sosfiltfilt, welch


CONDITIONS = (("8hz", 8.0), ("10hz", 10.0), ("12hz", 12.0), ("15hz", 15.0))
OCCIPITAL_CHANNELS = ("PO3", "PO4", "Oz", "O1", "O2")


def configure_plotting() -> None:
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


def load_condition(path: Path) -> tuple[float, dict[str, np.ndarray], float]:
    raw = mne.io.read_raw_bdf(path, preload=True, verbose="ERROR")
    missing = [channel for channel in OCCIPITAL_CHANNELS if channel not in raw.ch_names]
    if missing:
        raise ValueError(f"{path} 缺少枕区通道: {missing}")

    sample_rate = float(raw.info["sfreq"])
    duration = raw.n_times / sample_rate
    trim_seconds = 2.0 if duration >= 10.0 else 0.0
    start = int(trim_seconds * sample_rate)
    stop = raw.n_times - start
    if stop <= start:
        start, stop = 0, raw.n_times

    data_volts = raw.get_data(picks=list(OCCIPITAL_CHANNELS), start=start, stop=stop)
    data_uv = data_volts * 1e6
    sos = butter(4, [1.0, 40.0], btype="bandpass", fs=sample_rate, output="sos")
    filtered = sosfiltfilt(sos, detrend(data_uv, axis=1, type="linear"), axis=1)
    return sample_rate, dict(zip(OCCIPITAL_CHANNELS, filtered, strict=True)), duration


def spectrum_metrics(
    signal: np.ndarray,
    sample_rate: float,
    target_hz: float,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, dict[str, float | str]]:
    nperseg = min(int(sample_rate * 4), signal.size)
    noverlap = nperseg // 2
    frequencies, psd = welch(
        signal,
        fs=sample_rate,
        window="hann",
        nperseg=nperseg,
        noverlap=noverlap,
        detrend="constant",
        scaling="density",
    )

    centered = signal - signal.mean()
    fft_values = np.fft.rfft(centered * np.hanning(centered.size))
    fft_frequencies = np.fft.rfftfreq(centered.size, d=1.0 / sample_rate)
    fft_amplitude = 2.0 * np.abs(fft_values) / max(centered.size, 1)

    target_index = int(np.argmin(np.abs(frequencies - target_hz)))
    target_power = float(psd[target_index])
    neighbor_mask = (
        (frequencies >= target_hz - 2.0)
        & (frequencies <= target_hz + 2.0)
        & (np.abs(frequencies - target_hz) >= 0.5)
    )
    noise_power = float(np.median(psd[neighbor_mask])) if neighbor_mask.any() else np.nan
    snr_db = 10.0 * np.log10(target_power / max(noise_power, 1e-30))

    band_mask = (frequencies >= 5.0) & (frequencies <= 40.0)
    band_indices = np.flatnonzero(band_mask)
    peak_indices, _ = find_peaks(psd[band_mask], distance=max(1, int(0.75 / (frequencies[1] - frequencies[0]))))
    ranked = band_indices[peak_indices[np.argsort(psd[band_indices[peak_indices]])[::-1]]]
    top_peaks = ranked[:5]
    peak_text = "; ".join(f"{frequencies[index]:.2f}Hz" for index in top_peaks)

    harmonic_powers = {}
    for harmonic in (2, 3):
        frequency = target_hz * harmonic
        if frequency <= 40.0:
            index = int(np.argmin(np.abs(frequencies - frequency)))
            harmonic_powers[f"h{harmonic}_power"] = float(psd[index])
        else:
            harmonic_powers[f"h{harmonic}_power"] = np.nan

    metrics: dict[str, float | str] = {
        "target_power": target_power,
        "neighbor_noise_power": noise_power,
        "target_snr_db": float(snr_db),
        "dominant_frequency_hz": float(frequencies[band_indices[np.argmax(psd[band_mask])]]),
        "top_peaks": peak_text,
        "signal_std_uv": float(np.std(signal)),
        "signal_max_abs_uv": float(np.max(np.abs(signal))),
        **harmonic_powers,
    }
    return frequencies, psd, fft_frequencies, fft_amplitude, metrics


def plot_spectra(
    spectra: dict[str, tuple[np.ndarray, np.ndarray]],
    targets: dict[str, float],
    output: Path,
    title: str,
    ylabel: str,
) -> None:
    colors = {"8hz": "#2478C5", "10hz": "#00A087", "12hz": "#E18727", "15hz": "#C43C39"}
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), constrained_layout=True)
    for axis, (condition, target) in zip(axes.flat, targets.items(), strict=True):
        frequencies, values = spectra[condition]
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.plot(frequencies[mask], values[mask], color=colors[condition], linewidth=1.35)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.1, label=f"目标 {target:g} Hz")
        for harmonic in (2, 3):
            harmonic_frequency = target * harmonic
            if harmonic_frequency <= 40.0:
                axis.axvline(harmonic_frequency, color="#777777", linestyle=":", linewidth=0.9)
        axis.set_title(f"注视 {target:g} Hz")
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel(ylabel)
        axis.legend(loc="upper right", frameon=False)
    fig.suptitle(title, fontsize=15)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def main() -> None:
    parser = argparse.ArgumentParser(description="分析博睿康Recorder保存的四频SSVEP BDF数据")
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()

    configure_plotting()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    targets = dict(CONDITIONS)
    oz_psd: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    occipital_psd: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    oz_fft: dict[str, tuple[np.ndarray, np.ndarray]] = {}
    rows: list[dict[str, float | str]] = []
    summaries: list[str] = []

    for condition, target in CONDITIONS:
        bdf_path = args.input_root / condition / "data.bdf"
        sample_rate, channels, duration = load_condition(bdf_path)
        oz = channels["Oz"]
        occipital_average = np.mean(np.stack([channels[name] for name in OCCIPITAL_CHANNELS]), axis=0)

        f_oz, p_oz, f_fft, a_fft, oz_metrics = spectrum_metrics(oz, sample_rate, target)
        f_occ, p_occ, _, _, occ_metrics = spectrum_metrics(occipital_average, sample_rate, target)
        oz_psd[condition] = (f_oz, 10.0 * np.log10(np.maximum(p_oz, 1e-30)))
        occipital_psd[condition] = (f_occ, 10.0 * np.log10(np.maximum(p_occ, 1e-30)))
        oz_fft[condition] = (f_fft, a_fft)

        row = {
            "condition": condition,
            "target_hz": target,
            "sample_rate_hz": sample_rate,
            "duration_seconds": duration,
            **{f"oz_{key}": value for key, value in oz_metrics.items()},
            **{f"occipital_{key}": value for key, value in occ_metrics.items()},
        }
        rows.append(row)
        summaries.append(
            f"{target:g} Hz: Oz主峰={oz_metrics['dominant_frequency_hz']:.2f} Hz, "
            f"目标SNR={oz_metrics['target_snr_db']:.2f} dB, "
            f"枕区均值目标SNR={occ_metrics['target_snr_db']:.2f} dB"
        )

    plot_spectra(
        oz_psd,
        targets,
        args.output_dir / "01_Oz_Welch_PSD_四频对比.png",
        "Oz通道四频SSVEP Welch功率谱",
        "PSD (dB/Hz)",
    )
    plot_spectra(
        occipital_psd,
        targets,
        args.output_dir / "02_枕区五通道平均_Welch_PSD_四频对比.png",
        "PO3/PO4/Oz/O1/O2平均信号 Welch功率谱",
        "PSD (dB/Hz)",
    )
    plot_spectra(
        oz_fft,
        targets,
        args.output_dir / "03_Oz_FFT_四频对比.png",
        "Oz通道四频SSVEP FFT幅值谱",
        "幅值 (μV)",
    )

    csv_path = args.output_dir / "04_频谱指标.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    summary_path = args.output_dir / "00_分析摘要.txt"
    summary_path.write_text(
        "博睿康真人SSVEP离线频谱分析\n"
        f"输入目录: {args.input_root}\n"
        "分析通道: Oz及PO3/PO4/Oz/O1/O2平均\n"
        "预处理: 去趋势, 1-40 Hz四阶Butterworth零相位滤波, 去除首尾各2秒\n"
        "Welch: 4秒Hann窗, 50%重叠, 0.25 Hz频率分辨率\n\n"
        + "\n".join(summaries)
        + "\n",
        encoding="utf-8-sig",
    )
    print(summary_path.read_text(encoding="utf-8-sig"))
    print(f"输出目录: {args.output_dir}")


if __name__ == "__main__":
    main()
