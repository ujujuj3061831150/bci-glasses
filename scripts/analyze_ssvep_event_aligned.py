from __future__ import annotations

import argparse
import csv
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

import matplotlib.pyplot as plt
import mne
import numpy as np
from scipy.signal import butter, detrend, sosfiltfilt, welch


CONDITIONS = (("8hz", 8.0), ("10hz", 10.0), ("12hz", 12.0), ("15hz", 15.0))
TARGETS = (8.0, 10.0, 12.0, 15.0)
CHANNELS = ("PO3", "PO4", "O1", "O2")


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


def read_active_intervals(path: Path) -> list[tuple[int, datetime, datetime]]:
    rows = list(csv.DictReader(path.open(encoding="utf-8-sig")))
    starts: dict[int, datetime] = {}
    intervals: list[tuple[int, datetime, datetime]] = []
    timezone = ZoneInfo("Asia/Shanghai")
    for row in rows:
        session = int(row["session_id"])
        timestamp = datetime.fromtimestamp(int(row["timestamp_ms"]) / 1000.0, timezone).replace(tzinfo=None)
        if row["event"] == "ACTIVATED":
            starts[session] = timestamp
        elif row["event"] == "TIMED_OUT" and session in starts:
            intervals.append((session, starts[session], timestamp))
    return intervals


def calculate_snr(frequencies: np.ndarray, psd: np.ndarray, target: float) -> float:
    target_index = int(np.argmin(np.abs(frequencies - target)))
    noise_mask = (
        (frequencies >= target - 2.0)
        & (frequencies <= target + 2.0)
        & (np.abs(frequencies - target) >= 0.5)
    )
    noise = float(np.median(psd[noise_mask]))
    return float(10.0 * np.log10(float(psd[target_index]) / max(noise, 1e-30)))


def main() -> None:
    parser = argparse.ArgumentParser(description="按App ACTIVE时间戳切分Recorder BDF并分析SSVEP")
    parser.add_argument("--input-root", type=Path, required=True)
    parser.add_argument("--session-csv", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    args = parser.parse_args()
    configure_plotting()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    active_intervals = read_active_intervals(args.session_csv)

    trial_rows: list[dict[str, float | int | str]] = []
    averaged_psds: dict[str, tuple[np.ndarray, np.ndarray, int]] = {}
    summaries: list[str] = []

    for condition, intended in CONDITIONS:
        raw = mne.io.read_raw_bdf(args.input_root / condition / "data.bdf", preload=True, verbose="ERROR")
        sample_rate = float(raw.info["sfreq"])
        # Recorder BDF writes +00:00 but its clock value matches the PC local wall clock.
        bdf_start = raw.info["meas_date"].replace(tzinfo=None)
        bdf_end = bdf_start.timestamp() + raw.n_times / sample_rate
        data = raw.get_data(picks=list(CHANNELS)) * 1e6
        data = detrend(data, axis=1, type="linear")
        sos = butter(4, [1.0, 40.0], btype="bandpass", fs=sample_rate, output="sos")
        signal = np.mean(sosfiltfilt(sos, data, axis=1), axis=0)

        condition_psds: list[np.ndarray] = []
        condition_rows: list[dict[str, float | int | str]] = []
        for session, active_start, active_end in active_intervals:
            if not (bdf_start.timestamp() <= active_start.timestamp() < bdf_end):
                continue
            segment_start_s = (active_start - bdf_start).total_seconds() + 0.5
            segment_end_s = segment_start_s + 4.0
            if segment_end_s > raw.n_times / sample_rate or active_end < active_start:
                continue
            start = int(round(segment_start_s * sample_rate))
            stop = start + int(round(4.0 * sample_rate))
            segment = signal[start:stop]
            frequencies, psd = welch(
                segment,
                fs=sample_rate,
                window="hann",
                nperseg=segment.size,
                noverlap=0,
                detrend="constant",
                scaling="density",
            )
            snrs = np.array([calculate_snr(frequencies, psd, target) for target in TARGETS])
            harmonic_snrs = np.array([calculate_snr(frequencies, psd, target * 2.0) for target in TARGETS])
            combined_scores = (snrs + harmonic_snrs) / 2.0
            order = np.argsort(snrs)[::-1]
            combined_order = np.argsort(combined_scores)[::-1]
            winner = TARGETS[int(order[0])]
            combined_winner = TARGETS[int(combined_order[0])]
            row: dict[str, float | int | str] = {
                "recording": condition,
                "intended_hz": intended,
                "session_id": session,
                "active_start_local": active_start.isoformat(timespec="milliseconds"),
                "bdf_offset_start_s": segment_start_s,
                "bdf_offset_end_s": segment_end_s,
                **{f"snr_{target:g}hz_db": float(value) for target, value in zip(TARGETS, snrs, strict=True)},
                **{
                    f"snr_{target * 2:g}hz_h2_db": float(value)
                    for target, value in zip(TARGETS, harmonic_snrs, strict=True)
                },
                **{
                    f"combined_{target:g}hz_db": float(value)
                    for target, value in zip(TARGETS, combined_scores, strict=True)
                },
                "winner_hz": winner,
                "winner_margin_db": float(snrs[order[0]] - snrs[order[1]]),
                "correct": int(winner == intended),
                "combined_winner_hz": combined_winner,
                "combined_winner_margin_db": float(
                    combined_scores[combined_order[0]] - combined_scores[combined_order[1]]
                ),
                "combined_correct": int(combined_winner == intended),
                "signal_std_uv": float(np.std(segment)),
                "signal_max_abs_uv": float(np.max(np.abs(segment))),
            }
            condition_rows.append(row)
            trial_rows.append(row)
            condition_psds.append(psd)

        if condition_psds:
            mean_psd = np.mean(np.stack(condition_psds), axis=0)
            averaged_psds[condition] = (frequencies, mean_psd, len(condition_psds))
            correct = sum(int(row["correct"]) for row in condition_rows)
            combined_correct = sum(int(row["combined_correct"]) for row in condition_rows)
            intended_snrs = [float(row[f"snr_{intended:g}hz_db"]) for row in condition_rows]
            winners = ", ".join(f"{float(row['winner_hz']):g}" for row in condition_rows)
            combined_winners = ", ".join(
                f"{float(row['combined_winner_hz']):g}" for row in condition_rows
            )
            summaries.append(
                f"{intended:g} Hz: {len(condition_rows)}次有效试次, 频谱四分类正确 {correct}/{len(condition_rows)}, "
                f"基频+二次谐波正确 {combined_correct}/{len(condition_rows)}, "
                f"目标SNR中位数={np.median(intended_snrs):.2f} dB, "
                f"基频胜出=[{winners}] Hz, 组合胜出=[{combined_winners}] Hz"
            )

    csv_path = args.output_dir / "08_事件对齐试次指标.csv"
    with csv_path.open("w", newline="", encoding="utf-8-sig") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(trial_rows[0].keys()))
        writer.writeheader()
        writer.writerows(trial_rows)

    colors = {"8hz": "#2478C5", "10hz": "#00A087", "12hz": "#E18727", "15hz": "#C43C39"}
    fig, axes = plt.subplots(2, 2, figsize=(11, 7), constrained_layout=True)
    for axis, (condition, intended) in zip(axes.flat, CONDITIONS, strict=True):
        frequencies, psd, count = averaged_psds[condition]
        mask = (frequencies >= 5.0) & (frequencies <= 40.0)
        axis.plot(frequencies[mask], 10 * np.log10(np.maximum(psd[mask], 1e-30)), color=colors[condition])
        axis.axvline(intended, color="#222222", linestyle="--", linewidth=1.0, label=f"目标 {intended:g} Hz")
        if intended * 2 <= 40:
            axis.axvline(intended * 2, color="#777777", linestyle=":", linewidth=0.9, label="二次谐波")
        axis.set_title(f"{intended:g} Hz：{count}次ACTIVE试次平均")
        axis.set_xlabel("频率 (Hz)")
        axis.set_ylabel("PSD (dB/Hz)")
        axis.legend(frameon=False, fontsize=8)
    fig.suptitle("App时间戳对齐后的枕区四通道平均PSD（PO3/PO4/O1/O2，不含异常Oz）", fontsize=14)
    fig.savefig(args.output_dir / "09_事件对齐试次平均PSD.png", bbox_inches="tight")
    plt.close(fig)

    total_correct = sum(int(row["correct"]) for row in trial_rows)
    total_combined_correct = sum(int(row["combined_correct"]) for row in trial_rows)
    summary_path = args.output_dir / "00_事件对齐分析摘要.txt"
    summary_path.write_text(
        "Recorder BDF 与 Android App 刺激时间戳对齐分析\n"
        f"App日志: {args.session_csv}\n"
        "切段: 每次ACTIVATED后0.5秒开始，取4.0秒；分析PO3/PO4/O1/O2平均，排除异常Oz。\n"
        "注意: App日志未保存被点击目标ID，频率标签取自对应Recorder文件夹名。\n\n"
        + "\n".join(summaries)
        + f"\n\n仅基频合计正确: {total_correct}/{len(trial_rows)} ({total_correct / len(trial_rows):.1%})\n"
        + f"基频+二次谐波合计正确: {total_combined_correct}/{len(trial_rows)} "
        f"({total_combined_correct / len(trial_rows):.1%})\n",
        encoding="utf-8-sig",
    )
    print(summary_path.read_text(encoding="utf-8-sig"))
    print(f"输出目录: {args.output_dir}")


if __name__ == "__main__":
    main()
