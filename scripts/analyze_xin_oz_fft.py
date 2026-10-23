from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import mne
import numpy as np
from scipy.signal import butter, detrend, find_peaks, sosfiltfilt, welch


CONDITIONS = (("新8hz", 8.0), ("新10hz", 10.0), ("新12hz", 12.0), ("新15hz", 15.0))
CANDIDATES = (8.0, 10.0, 12.0, 15.0)
COLORS = {8.0: "#2478C5", 10.0: "#00A087", 12.0: "#E18727", 15.0: "#C43C39"}


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


def load_oz(path: Path) -> tuple[float, np.ndarray, float]:
    raw = mne.io.read_raw_bdf(path, preload=True, verbose="ERROR")
    if "Oz" not in raw.ch_names:
        raise ValueError(f"{path} 缺少 Oz 通道")
    sample_rate = float(raw.info["sfreq"])
    signal_uv = raw.get_data(picks=["Oz"])[0] * 1e6
    if signal_uv.size == 0:
        return sample_rate, signal_uv, 0.0
    signal_uv = detrend(signal_uv, type="linear")
    sos = butter(4, [1.0, 40.0], btype="bandpass", fs=sample_rate, output="sos")
    filtered = sosfiltfilt(sos, signal_uv)
    return sample_rate, filtered, raw.n_times / sample_rate


def load_raw_oz(path: Path) -> tuple[float, np.ndarray, float]:
    raw = mne.io.read_raw_bdf(path, preload=True, verbose="ERROR")
    sample_rate = float(raw.info["sfreq"])
    signal_uv = raw.get_data(picks=["Oz"])[0] * 1e6
    return sample_rate, detrend(signal_uv, type="linear"), raw.n_times / sample_rate


def fft_spectrum(signal: np.ndarray, sample_rate: float) -> tuple[np.ndarray, np.ndarray]:
    centered = signal - np.mean(signal)
    window = np.hanning(centered.size)
    coherent_gain = np.mean(window)
    values = np.fft.rfft(centered * window)
    frequencies = np.fft.rfftfreq(centered.size, d=1.0 / sample_rate)
    amplitude = 2.0 * np.abs(values) / max(centered.size * coherent_gain, 1.0)
    return frequencies, amplitude


def local_snr_db(frequencies: np.ndarray, values: np.ndarray, target: float) -> float:
    target_index = int(np.argmin(np.abs(frequencies - target)))
    noise_mask = (
        (frequencies >= target - 2.0)
        & (frequencies <= target + 2.0)
        & (np.abs(frequencies - target) >= 0.5)
    )
    noise = float(np.median(values[noise_mask]))
    return float(20.0 * np.log10(float(values[target_index]) / max(noise, 1e-30)))


def local_power_snr_db(frequencies: np.ndarray, values: np.ndarray, target: float) -> float:
    target_index = int(np.argmin(np.abs(frequencies - target)))
    noise_mask = (
        (frequencies >= target - 2.0)
        & (frequencies <= target + 2.0)
        & (np.abs(frequencies - target) >= 0.5)
    )
    noise = float(np.median(values[noise_mask]))
    return float(10.0 * np.log10(float(values[target_index]) / max(noise, 1e-30)))


def top_peaks(frequencies: np.ndarray, amplitude: np.ndarray) -> str:
    mask = (frequencies >= 5.0) & (frequencies <= 40.0)
    band_indices = np.flatnonzero(mask)
    spacing = max(1, int(round(0.5 / (frequencies[1] - frequencies[0]))))
    peaks, _ = find_peaks(amplitude[mask], distance=spacing)
    ranked = band_indices[peaks[np.argsort(amplitude[band_indices[peaks]])[::-1]]]
    return "; ".join(
        f"{frequencies[index]:.3f}Hz({amplitude[index]:.3f}uV)" for index in ranked[:8]
    )


def sliding_target_snr(
    signal: np.ndarray,
    sample_rate: float,
    target: float,
    window_seconds: float = 4.0,
    step_seconds: float = 0.5,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    window_samples = int(round(window_seconds * sample_rate))
    step_samples = int(round(step_seconds * sample_rate))
    times: list[float] = []
    snrs: list[float] = []
    winners: list[float] = []
    for start in range(0, signal.size - window_samples + 1, step_samples):
        segment = signal[start : start + window_samples]
        frequencies, psd = welch(
            segment,
            fs=sample_rate,
            window="hann",
            nperseg=window_samples,
            noverlap=0,
            detrend="constant",
            scaling="density",
        )
        candidate_snrs = np.array(
            [10.0 * np.log10(max(psd[np.argmin(np.abs(frequencies - candidate))], 1e-30) /
             max(np.median(psd[(frequencies >= candidate - 2.0) &
                               (frequencies <= candidate + 2.0) &
                               (np.abs(frequencies - candidate) >= 0.5)]), 1e-30))
             for candidate in CANDIDATES]
        )
        times.append((start + window_samples / 2.0) / sample_rate)
        snrs.append(float(candidate_snrs[CANDIDATES.index(target)]))
        winners.append(CANDIDATES[int(np.argmax(candidate_snrs))])
    return np.asarray(times), np.asarray(snrs), np.asarray(winners)


def main() -> None:
    parser = argparse.ArgumentParser(description="分析 xin 四频录制中的 Oz FFT/PSD")
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    configure_plotting()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    primary: dict[float, tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray, float, np.ndarray, np.ndarray]] = {}
    primary_signals: dict[float, tuple[float, np.ndarray, np.ndarray, np.ndarray]] = {}
    rows: list[dict[str, float | str | int]] = []
    sliding_rows: list[dict[str, float | str]] = []

    for folder, target in CONDITIONS:
        segment_paths = sorted((args.input_root / folder).glob("data*.bdf"))
        valid_segment_number = 0
        for path in segment_paths:
            try:
                sample_rate, signal, duration = load_oz(path)
            except IndexError:
                continue
            if signal.size == 0:
                continue
            valid_segment_number += 1
            fft_frequencies, fft_amplitude = fft_spectrum(signal, sample_rate)
            welch_frequencies, psd = welch(
                signal,
                fs=sample_rate,
                window="hann",
                nperseg=min(int(sample_rate * 4), signal.size),
                noverlap=min(int(sample_rate * 2), max(0, signal.size - 1)),
                detrend="constant",
                scaling="density",
            )
            times, target_snrs, winners = sliding_target_snr(signal, sample_rate, target)
            if path.name == "data.bdf":
                primary[target] = (
                    fft_frequencies,
                    fft_amplitude,
                    welch_frequencies,
                    psd,
                    duration,
                    times,
                    target_snrs,
                )
                primary_signals[target] = (sample_rate, signal, times, target_snrs)

            target_index = int(np.argmin(np.abs(fft_frequencies - target)))
            local_mask = np.abs(fft_frequencies - target) <= 0.5
            local_indices = np.flatnonzero(local_mask)
            local_peak_index = int(local_indices[np.argmax(fft_amplitude[local_mask])])
            candidate_amplitudes = {
                candidate: float(fft_amplitude[np.argmin(np.abs(fft_frequencies - candidate))])
                for candidate in CANDIDATES
            }
            candidate_snrs = {
                candidate: local_snr_db(fft_frequencies, fft_amplitude, candidate)
                for candidate in CANDIDATES
            }
            winner = max(candidate_snrs, key=candidate_snrs.get)
            welch_candidate_snrs = {
                candidate: local_power_snr_db(welch_frequencies, psd, candidate)
                for candidate in CANDIDATES
            }
            welch_winner = max(welch_candidate_snrs, key=welch_candidate_snrs.get)
            best_window_index = int(np.argmax(target_snrs))
            rows.append(
                {
                    "folder": folder,
                    "segment": path.name,
                    "intended_hz": target,
                    "sample_rate_hz": sample_rate,
                    "duration_s": duration,
                    "oz_std_uv": float(np.std(signal)),
                    "oz_max_abs_uv": float(np.max(np.abs(signal))),
                    "target_fft_bin_hz": float(fft_frequencies[target_index]),
                    "target_fft_modulus_uv": float(fft_amplitude[target_index]),
                    "target_fft_snr_db": candidate_snrs[target],
                    "local_peak_hz_within_0_5hz": float(fft_frequencies[local_peak_index]),
                    "local_peak_modulus_uv": float(fft_amplitude[local_peak_index]),
                    "fft_candidate_winner_hz": winner,
                    "welch_target_snr_db": welch_candidate_snrs[target],
                    "welch_h2_snr_db": local_power_snr_db(welch_frequencies, psd, target * 2.0),
                    "welch_candidate_winner_hz": welch_winner,
                    **{f"fft_modulus_{candidate:g}hz_uv": value for candidate, value in candidate_amplitudes.items()},
                    **{f"fft_snr_{candidate:g}hz_db": value for candidate, value in candidate_snrs.items()},
                    "top_fft_peaks_5_40hz": top_peaks(fft_frequencies, fft_amplitude),
                    "sliding_windows": int(target_snrs.size),
                    "sliding_target_snr_median_db": float(np.median(target_snrs)),
                    "sliding_target_snr_p90_db": float(np.percentile(target_snrs, 90)),
                    "sliding_target_snr_best_db": float(np.max(target_snrs)),
                    "sliding_best_window_start_s": float(times[best_window_index] - 2.0),
                    "sliding_best_window_end_s": float(times[best_window_index] + 2.0),
                    "sliding_best_window_winner_hz": float(winners[best_window_index]),
                    "sliding_fraction_above_3db": float(np.mean(target_snrs >= 3.0)),
                    "sliding_fraction_target_wins": float(np.mean(winners == target)),
                }
            )
            for time, snr, window_winner in zip(times, target_snrs, winners, strict=True):
                sliding_rows.append(
                    {
                        "folder": folder,
                        "segment": path.name,
                        "intended_hz": target,
                        "center_s": float(time),
                        "target_snr_db": float(snr),
                        "winner_hz": float(window_winner),
                    }
                )

    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (_, target) in zip(axes.flat, CONDITIONS, strict=True):
        frequencies, amplitude, _, _, duration, _, _ = primary[target]
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.semilogy(frequencies[mask], np.maximum(amplitude[mask], 1e-6), color=COLORS[target], linewidth=1.0)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.1, label=f"目标 {target:g} Hz")
        if target * 2 <= 40:
            axis.axvline(target * 2, color="#777777", linestyle=":", linewidth=0.9, label="二次谐波")
        axis.set_title(f"注视标签 {target:g} Hz，整段 {duration:.0f}s")
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("FFT取模/幅值 (uV，对数轴)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle("新录制 Oz 通道整段 FFT 取模", fontsize=15)
    fig.savefig(args.output_dir / "01_Oz_整段FFT取模_四频.png", bbox_inches="tight")
    plt.close(fig)

    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (_, target) in zip(axes.flat, CONDITIONS, strict=True):
        frequencies, amplitude, _, _, _, _, _ = primary[target]
        mask = (frequencies >= target - 3.0) & (frequencies <= target + 3.0)
        axis.plot(frequencies[mask], amplitude[mask], color=COLORS[target], linewidth=1.1)
        target_index = int(np.argmin(np.abs(frequencies - target)))
        axis.scatter([frequencies[target_index]], [amplitude[target_index]], color="#111111", s=22, zorder=3)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.0)
        axis.set_title(f"{target:g} Hz附近：目标模值 {amplitude[target_index]:.3f} uV")
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("FFT取模/幅值 (uV)")
    fig.suptitle("Oz 通道目标频率附近 FFT 局部放大", fontsize=15)
    fig.savefig(args.output_dir / "02_Oz_目标频率附近FFT放大.png", bbox_inches="tight")
    plt.close(fig)

    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (_, target) in zip(axes.flat, CONDITIONS, strict=True):
        _, _, frequencies, psd, _, _, _ = primary[target]
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.plot(frequencies[mask], 10.0 * np.log10(np.maximum(psd[mask], 1e-30)), color=COLORS[target])
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.0, label=f"目标 {target:g} Hz")
        if target * 2 <= 40:
            axis.axvline(target * 2, color="#777777", linestyle=":", linewidth=0.9)
        axis.set_title(f"注视标签 {target:g} Hz")
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("Welch PSD (dB/Hz)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle("新录制 Oz 通道 Welch 功率谱（4秒窗）", fontsize=15)
    fig.savefig(args.output_dir / "03_Oz_Welch_PSD_四频.png", bbox_inches="tight")
    plt.close(fig)

    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (_, target) in zip(axes.flat, CONDITIONS, strict=True):
        _, _, _, _, _, times, snrs = primary[target]
        axis.plot(times, snrs, color=COLORS[target], linewidth=1.4)
        axis.axhline(3.0, color="#555555", linestyle="--", linewidth=0.9, label="3 dB参考线")
        axis.set_title(f"{target:g} Hz目标：中位 {np.median(snrs):.2f} dB，最佳 {np.max(snrs):.2f} dB")
        axis.set_xlabel("录制时间 (s)")
        axis.set_ylabel("4秒窗目标SNR (dB)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle("Oz 通道目标频率滑窗 SNR（步长0.5秒）", fontsize=15)
    fig.savefig(args.output_dir / "04_Oz_滑窗目标SNR.png", bbox_inches="tight")
    plt.close(fig)

    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (_, target) in zip(axes.flat, CONDITIONS, strict=True):
        sample_rate, signal, times, snrs = primary_signals[target]
        best_index = int(np.argmax(snrs))
        start = int(round((times[best_index] - 2.0) * sample_rate))
        stop = start + int(round(4.0 * sample_rate))
        frequencies, amplitude = fft_spectrum(signal[start:stop], sample_rate)
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.plot(frequencies[mask], amplitude[mask], color=COLORS[target], linewidth=1.2)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.0, label=f"目标 {target:g} Hz")
        if target * 2 <= 40:
            axis.axvline(target * 2, color="#777777", linestyle=":", linewidth=0.9, label="二次谐波")
        axis.set_title(
            f"{target:g} Hz：{times[best_index] - 2.0:.1f}-{times[best_index] + 2.0:.1f}s，"
            f"SNR {snrs[best_index]:.2f} dB"
        )
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("FFT取模/幅值 (uV)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle("Oz 目标频率 SNR 最高的 4 秒窗 FFT（定位候选有效刺激段）", fontsize=15)
    fig.savefig(args.output_dir / "07_Oz_最佳4秒窗FFT.png", bbox_inches="tight")
    plt.close(fig)

    raw_rows: list[dict[str, float | str]] = []
    fig, axes = plt.subplots(2, 2, figsize=(11.5, 7.5), constrained_layout=True)
    for axis, (folder, target) in zip(axes.flat, CONDITIONS, strict=True):
        sample_rate, raw_signal, duration = load_raw_oz(args.input_root / folder / "data.bdf")
        frequencies, amplitude = fft_spectrum(raw_signal, sample_rate)
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        target_index = int(np.argmin(np.abs(frequencies - target)))
        target_snr = local_snr_db(frequencies, amplitude, target)
        axis.semilogy(frequencies[mask], np.maximum(amplitude[mask], 1e-6), color=COLORS[target], linewidth=1.0)
        axis.axvline(target, color="#222222", linestyle="--", linewidth=1.0)
        axis.set_title(
            f"{target:g} Hz标签：模值 {amplitude[target_index]:.3f} uV，SNR {target_snr:.2f} dB"
        )
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("原始Oz FFT取模/幅值 (uV，对数轴)")
        raw_rows.append(
            {
                "folder": folder,
                "intended_hz": target,
                "duration_s": duration,
                "raw_target_fft_modulus_uv": float(amplitude[target_index]),
                "raw_target_fft_snr_db": target_snr,
                "raw_top_fft_peaks_5_40hz": top_peaks(frequencies, amplitude),
            }
        )
    fig.suptitle("Oz 原始数据 FFT 取模对照（仅去线性趋势，未带通滤波）", fontsize=15)
    fig.savefig(args.output_dir / "08_Oz_原始数据FFT取模_未带通.png", bbox_inches="tight")
    plt.close(fig)

    raw_metrics_path = args.output_dir / "09_Oz_原始FFT指标.csv"
    with raw_metrics_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(raw_rows[0].keys()))
        writer.writeheader()
        writer.writerows(raw_rows)

    metrics_path = args.output_dir / "05_Oz_FFT与峰值指标.csv"
    with metrics_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)

    sliding_path = args.output_dir / "06_Oz_滑窗指标.csv"
    with sliding_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(sliding_rows[0].keys()))
        writer.writeheader()
        writer.writerows(sliding_rows)

    primary_rows = [row for row in rows if row["segment"] == "data.bdf"]
    lines = [
        "xin 新录制 Oz FFT/PSD 分析",
        f"输入目录: {args.input_root}",
        "预处理: 去线性趋势 + 1-40 Hz 四阶 Butterworth 零相位滤波",
        "事件状态: BDF 无 annotation，evt.bdf 无有效刺激 Marker；无法按 App ACTIVE 精确切段。",
        "离线采集状态: 23:03:45 才启动，晚于四频录制；未检测到 ADB，未保存 App 刺激日志。",
        "",
    ]
    for row in primary_rows:
        target = float(row["intended_hz"])
        has_peak = float(row["target_fft_snr_db"]) >= 3.0
        lines.append(
            f"{target:g} Hz: 目标FFT模值={float(row['target_fft_modulus_uv']):.3f} uV, "
            f"目标FFT SNR={float(row['target_fft_snr_db']):.2f} dB, "
            f"目标附近局部峰={float(row['local_peak_hz_within_0_5hz']):.3f} Hz, "
            f"四候选FFT胜出={float(row['fft_candidate_winner_hz']):g} Hz, "
            f"Welch目标SNR={float(row['welch_target_snr_db']):.2f} dB, "
            f"Welch四候选胜出={float(row['welch_candidate_winner_hz']):g} Hz, "
            f"4秒窗中位SNR={float(row['sliding_target_snr_median_db']):.2f} dB, "
            f"目标胜出比例={float(row['sliding_fraction_target_wins']):.1%}, "
            f"结论={'存在>=3dB目标峰' if has_peak else '整段目标峰不足3dB'}"
        )
    summary_path = args.output_dir / "00_分析结论.txt"
    summary_path.write_text("\n".join(lines) + "\n", encoding="utf-8-sig")
    print(summary_path.read_text(encoding="utf-8-sig"))
    print(f"输出目录: {args.output_dir}")


if __name__ == "__main__":
    main()
