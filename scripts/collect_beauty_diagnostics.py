"""采集SDK数值诊断，不打开摄像头、不抓屏、不保存人脸或坐标。"""
import argparse
import datetime
import json
import queue
import re
import subprocess
import threading
import time
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="adb目标设备，必须显式选择")
    parser.add_argument("--duration", type=int, default=30, help="采集秒数，1至600")
    parser.add_argument("--scenario", choices=["original", "beauty", "beauty_stickers"], required=True,
                        help="用户已在Demo设置好的场景；脚本不会替你切换效果")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not 1 <= args.duration <= 600:
        parser.error("duration must be 1..600")
    adb = ["adb", "-s", args.serial]

    def shell(*command):
        result = subprocess.run(adb + ["shell", *command], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=15, check=True)
        return result.stdout.strip()

    def health():
        memory = shell("dumpsys", "meminfo", "com.beautysdk.demo")
        pss = re.search(r"TOTAL PSS:\s*(\d+)", memory)
        if not pss:
            pss = re.search(r"^\s*TOTAL\s+(\d+)", memory, re.M)
        battery = shell("dumpsys", "battery")
        temperature = re.search(r"^\s*temperature:\s*(-?\d+)", battery, re.M)
        return {"totalPssKiB": int(pss[1]) if pss else None,
                "batteryTemperatureC": int(temperature[1]) / 10 if temperature else None}

    report = {"startedUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "model": shell("getprop", "ro.product.model"),
              "android": shell("getprop", "ro.build.version.release"),
              "scenarioDeclaredByOperator": args.scenario,
              "durationSeconds": args.duration,
              "limits": ["CPU submission is not GPU completion or end-to-end latency",
                         "Local consumed video FPS is not encoder/remote FPS",
                         "Battery temperature is not CPU/GPU junction temperature",
                         "Scenario, light, face movement and warmup must be controlled by operator"],
              "before": health(), "windows": []}
    process = subprocess.Popen(adb + ["logcat", "-v", "brief", "-T", "1", "-s",
                                    "BeautyFrameWindow:D", "BeautyAnalysisWindow:D",
                                    "BeautyTracking:D", "BeautyMotion:D", "*:S"],
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                               encoding="utf-8", errors="replace")
    messages = queue.Queue(maxsize=1024)

    def read():
        for line in process.stdout:
            # 仅保留这些固定标签的数值诊断，绝不收集任意应用日志。
            if re.match(r"D/Beauty(FrameWindow|AnalysisWindow|Tracking|Motion)\s*\(", line):
                try:
                    messages.put_nowait(line.strip())
                except queue.Full:
                    pass

    thread = threading.Thread(target=read, daemon=True)
    thread.start()
    deadline = time.monotonic() + args.duration
    try:
        while time.monotonic() < deadline:
            try:
                report["windows"].append(messages.get(timeout=min(.5, max(.01, deadline-time.monotonic()))))
            except queue.Empty:
                if process.poll() is not None:
                    raise RuntimeError("adb logcat exited before collection completed")
    finally:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
        thread.join(timeout=1)
    report["after"] = health()
    report["frameWindows"] = sum("D/BeautyFrameWindow" in x for x in report["windows"])
    report["hasFrameDiagnostics"] = report["frameWindows"] >= 2
    output = args.output or Path("build/functional-test") / ("beauty-diagnostics-" +
             datetime.datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{output}: frameWindows={report['frameWindows']}; hasFrameDiagnostics={report['hasFrameDiagnostics']}")


if __name__ == "__main__":
    main()
