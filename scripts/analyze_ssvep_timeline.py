from __future__ import annotations

import argparse
import csv
from pathlib import Path

import matplotlib.pyplot as plt
import mne
import numpy as np
from scipy.signal import butter, detrend, sosfiltfilt, welch


CONDITIONS = (("8hz", 8.0), ("10hz", 10.0), ("12hz", 12.0), ("15hz", 15.0))
TARGETS = np.array([8.0, 10.0, 12.0, 15.0])
OCCIPITAL_CHANNELS = ("PO3", "PO4", "Oz", "O1", "O2")
CLEAN_OCCIPITAL_CHANNELS = ("PO3", "PO4", "O1", "O2")


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


def load_signals(path: Path) -> tuple[float, float, dict[str, np.ndarray]]:
    raw = mne.io.read_raw_bdf(path, preload=True, verbose="ERROR")
    missing = [name for name in OCCIPITAL_CHANNELS if name not in raw.ch_names]
    if missing:
        raise ValueError(f"{path} 缺少枕区通道: {missing}")
    sample_rate = float(raw.info["sfreq"])
    data_uv = raw.get_data(picks=list(OCCIPITAL_CHANNELS)) * 1e6
    data_uv = detrend(data_uv, axis=1, type="linear")
    sos = butter(4, [1.0, 40.0], btype="bandpass", fs=sample_rate, output="sos")
    filtered = sosfiltfilt(sos, data_uv, axis=1)
    signals = dict(zip(OCCIPITAL_CHANNELS, filtered, strict=True))
    signals["枕区均值"] = np.mean(filtered, axis=0)
    signals["枕区四通道均值(不含Oz)"] = np.mean(
        np.stack([signals[name] for name in CLEAN_OCCIPITAL_CHANNELS]), axis=0
    )
    return sample_rate, raw.n_times / sample_rate, signals


def calculate_snr(frequencies: np.ndarray, psd: np.ndarray, target: float) -> float:
    target_mask = np.abs(frequencies - target) <= 0.125
    noise_mask = (
        (frequencies >= target - 2.0)
        & (frequencies <= target + 2.0)
        & (np.abs(frequencies - target) >= 0.5)
    )
    target_power = float(np.mean(psd[target_mask]))
    noise_power = float(np.median(psd[noise_mask]))
    return float(10.0 * np.log10(target_power / max(noise_power, 1e-30)))


def window_metrics(
    signal: np.ndarray,
    sample_rate: float,
    window_seconds: float,
    step_seconds: float,
) -> list[dict[str, float]]:
    window_samples = int(round(window_seconds * sample_rate))
    step_samples = int(round(step_seconds * sample_rate))
    rows: list[dict[str, float]] = []
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
        snrs = np.array([calculate_snr(frequencies, psd, target) for target in TARGETS])
        winner_index = int(np.argmax(snrs))
        rows.append(
            {
                "start_s": start / sample_rate,
                "center_s": (start + window_samples / 2) / sample_rate,
                "end_s": (start + window_samples) / sample_rate,
                **{f"snr_{target:g}hz_db": float(value) for target, value in zip(TARGETS, snrs, strict=True)},
                "winner_hz": float(TARGETS[winner_index]),
                "winner_snr_db": float(snrs[winner_index]),
                "signal_std_uv": float(np.std(segment)),
                "signal_max_abs_uv": float(np.max(np.abs(segment))),
            }
        )
    return rows


def plot_timeline(
    all_rows: dict[str, list[dict[str, float]]],
    output: Path,
    signal_label: str,
) -> None:
    colors = {8.0: "#2478C5", 10.0: "#00A087", 12.0: "#E18727", 15.0: "#C43C39"}
    fig, axes = plt.subplots(2, 2, figsize=(12, 7.5), constrained_layout=True)
    for axis, (condition, intended) in zip(axes.flat, CONDITIONS, strict=True):
        rows = all_rows[condition]
        times = np.array([row["center_s"] for row in rows])
        for target in TARGETS:
            values = [row[f"snr_{target:g}hz_db"] for row in rows]
            width = 2.0 if target == intended else 0.9
            alpha = 1.0 if target == intended else 0.58
            axis.plot(times, values, color=colors[target], linewidth=width, alpha=alpha, label=f"{target:g} Hz")
        axis.axhline(3.0, color="#555555", linestyle="--", linewidth=0.9, label="3 dB参考线")
        axis.set_title(f"录制标签 {intended:g} Hz")
        axis.set_xlabel("录制时间 (s)")
        axis.set_ylabel("目标邻域 SNR (dB)")
        axis.legend(ncol=3, fontsize=8, frameon=False)
    fig.suptitle(f"{signal_label}：4秒滑窗四候选频率SNR（步长0.5秒）", fontsize=14)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def plot_best_window_psd(
    signals: dict[str, tuple[float, np.ndarray, list[dict[str, float]]]],
    output: Path,
    signal_label: str,
) -> None:
    colors = {"8hz": "#2478C5", "10hz": "#00A087", "12hz": "#E18727", "15hz": "#C43C39"}
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), constrained_layout=True)
    for axis, (condition, intended) in zip(axes.flat, CONDITIONS, strict=True):
        sample_rate, signal, rows = signals[condition]
        key = f"snr_{intended:g}hz_db"
        best = max(rows, key=lambda row: row[key])
        start = int(round(best["start_s"] * sample_rate))
        stop = int(round(best["end_s"] * sample_rate))
        frequencies, psd = welch(
            signal[start:stop],
            fs=sample_rate,
            window="hann",
            nperseg=stop - start,
            noverlap=0,
            detrend="constant",
            scaling="density",
        )
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.plot(frequencies[mask], 10 * np.log10(np.maximum(psd[mask], 1e-30)), color=colors[condition])
        axis.axvline(intended, color="#222222", linestyle="--", linewidth=1.0)
        if intended * 2 <= 40:
            axis.axvline(intended * 2, color="#777777", linestyle=":", linewidth=0.9)
        axis.set_title(
            f"{intended:g} Hz标签：最佳窗 {best['start_s']:.1f}-{best['end_s']:.1f}s，SNR {best[key]:.2f} dB"
        )
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("PSD (dB/Hz)")
    fig.suptitle(f"{signal_label}：目标频率SNR最高的4秒窗（仅用于定位，不代表整体准确率）", fontsize=14)
    fig.savefig(output, bbox_inches="tight")
    plt.close(fig)


def summarize_rows(rows: list[dict[str, float]], intended: float) -> dict[str, float]:
    key = f"snr_{intended:g}hz_db"
    values = np.array([row[key] for row in rows])
    winners = np.array([row["winner_hz"] for row in rows])
    best_index = int(np.argmax(values))
    return {
        "median_snr_db": float(np.median(values)),
        "p90_snr_db": float(np.percentile(values, 90)),
        "best_snr_db": float(values[best_index]),
        "best_start_s": rows[best_index]["start_s"],
        "best_end_s": rows[best_index]["end_s"],
        "fraction_above_3db": float(np.mean(values >= 3.0)),
        "fraction_target_wins": float(np.mean(winners == intended)),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="对Recorder BDF执行SSVEP滑窗SNR分析")
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    configure_plotting()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    rows_for_csv: list[dict[str, float | str]] = []
    summaries: list[str] = []
    for signal_label, output_stem in (
        ("Oz", "Oz"),
        ("枕区均值", "枕区五通道平均"),
        ("枕区四通道均值(不含Oz)", "枕区四通道平均_不含Oz"),
    ):
        all_rows: dict[str, list[dict[str, float]]] = {}
        plot_signals: dict[str, tuple[float, np.ndarray, list[dict[str, float]]]] = {}
        summaries.append(f"\n[{signal_label}]")
        for condition, intended in CONDITIONS:
            sample_rate, duration, signals = load_signals(args.input_root / condition / "data.bdf")
            rows = window_metrics(signals[signal_label], sample_rate, 4.0, 0.5)
            all_rows[condition] = rows
            plot_signals[condition] = (sample_rate, signals[signal_label], rows)
            summary = summarize_rows(rows, intended)
            summaries.append(
                f"{intended:g} Hz标签: 中位SNR={summary['median_snr_db']:.2f} dB, "
                f"P90={summary['p90_snr_db']:.2f} dB, 最佳={summary['best_snr_db']:.2f} dB "
                f"({summary['best_start_s']:.1f}-{summary['best_end_s']:.1f}s), "
                f">=3dB窗比例={summary['fraction_above_3db']:.1%}, "
                f"四频竞争胜出比例={summary['fraction_target_wins']:.1%}"
            )
            for row in rows:
                rows_for_csv.append(
                    {
                        "signal": signal_label,
                        "recording": condition,
                        "intended_hz": intended,
                        "duration_s": duration,
                        **row,
                    }
                )
        plot_timeline(all_rows, args.output_dir / f"05_{output_stem}_滑窗SNR时间线.png", signal_label)
        plot_best_window_psd(plot_signals, args.output_dir / f"06_{output_stem}_最佳窗PSD.png", signal_label)

    csv_path = args.output_dir / "07_滑窗频谱指标.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows_for_csv[0].keys()))
        writer.writeheader()
        writer.writerows(rows_for_csv)

    summary_path = args.output_dir / "00_滑窗分析摘要.txt"
    summary_path.write_text(
        "博睿康真人SSVEP滑窗分析\n"
        f"输入目录: {args.input_root}\n"
        "事件检查: data.bdf无annotation；evt.bdf无可由MNE读取的有效事件，无法按刺激Marker精确切段。\n"
        "方法: 1-40 Hz零相位滤波；4秒Hann窗；0.5秒步长；同时比较8/10/12/15 Hz。\n"
        "解释: 最佳窗用于定位候选有效时段；是否可靠应同时看中位数、>=3 dB比例和四频竞争胜出比例。\n"
        + "\n".join(summaries)
        + "\n",
        encoding="utf-8-sig",
    )
    print(summary_path.read_text(encoding="utf-8-sig"))
    print(f"输出目录: {args.output_dir}")


if __name__ == "__main__":
    main()
