"""Local Recorder-compatible 8/32 EEG + 1 Event, 1000 Hz Float32 smoke server."""

from __future__ import annotations

import argparse
import socket
import struct
import time

import numpy as np


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8712)
    parser.add_argument("--eeg-channels", type=int, choices=(8, 32), default=8)
    args = parser.parse_args()
    sample_rate = 1000
    packet_samples = 40
    channels = args.eeg_channels + 1
    sequence = 0
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(2)
        print(
            f"Mock calibration Recorder ({args.eeg_channels} EEG + 1 Event) listening on {args.host}:{args.port}",
            flush=True,
        )
        while True:
            connection, address = server.accept()
            print(f"Client connected: {address}", flush=True)
            with connection:
                next_send = time.perf_counter()
                try:
                    while True:
                        indices = np.arange(sequence, sequence + packet_samples, dtype=np.float64)
                        t = indices / sample_rate
                        packet = np.zeros((packet_samples, channels), dtype="<f4")
                        for channel in range(args.eeg_channels):
                            frequency = (8.0, 10.0, 12.0, 15.0)[channel % 4]
                            packet[:, channel] = (
                                18.0 * np.sin(2 * np.pi * frequency * t + channel * 0.31)
                                + 3.0 * np.sin(2 * np.pi * frequency * 2 * t + channel * 0.17)
                                + 0.4 * np.sin(2 * np.pi * 3.0 * t + channel)
                            )
                        packet[:, args.eeg_channels] = 0.0
                        connection.sendall(packet.tobytes(order="C"))
                        sequence += packet_samples
                        next_send += packet_samples / sample_rate
                        time.sleep(max(0.0, next_send - time.perf_counter()))
                except OSError as exception:
                    print(f"Client disconnected: {exception}", flush=True)


if __name__ == "__main__":
    main()
