"""Chronos-Bolt-Tiny 를 ONNX 로 내보내고, 공식 파이프라인(PyTorch)과 onnxruntime 의 다음 1스텝 분위수를 견준다.

관문(설계 A2): 합성 주간 시계열 20개 × 분위수 9개에서 최대 상대 오차가 1% 이하면 통과.
통과하면 ONNX 를 car/app/src/main/assets/chronos_bolt_tiny.onnx 로 둔다. 실패하면 넣지 않는다.

공식 저장소(amazon-science/chronos-forecasting)와 chronos-forecasting 2.3.2 에는 ONNX 내보내기 스크립트가 없어,
공식 ChronosBoltModelForForecasting.forward 를 그대로 부르는 얇은 감싸개를 torch.onnx.export 로 내보낸다.
모델 코드는 손대지 않는다.

의존성(이 버전으로 확인, Python 3.13 / x86_64 / CPU):
    pip install chronos-forecasting==2.3.2 torch==2.9.1 transformers==4.57.3 accelerate==1.12.0 \
        huggingface_hub==0.36.0 onnx==1.19.1 onnxscript==0.7.2 onnx-ir==1.0.0 onnxruntime==1.31.0 numpy==2.3.5
    onnxscript 0.5.x 는 dtype 없는 aten::full(정수 채움)을 float 로 바꿔 [REG] 토큰 Gather 가 무효가 된다. 0.7.2 에서 고쳐졌다.

실행(저장소 루트에서):
    python3 tools/chronos/export_and_check.py              # Hugging Face 에서 REVISION 을 받는다
    python3 tools/chronos/export_and_check.py --model DIR  # 미리 받아 둔 폴더(config.json, model.safetensors)
"""
import argparse
import hashlib
import json
import shutil
import statistics
import sys
import tempfile
import time
from pathlib import Path

import chronos
import numpy as np
import onnx
import onnxruntime as ort
import onnxscript
import torch
import transformers
from chronos import ChronosBoltPipeline

REPO = "amazon/chronos-bolt-tiny"
# 고정한 Hugging Face 커밋. None 이면 실행할 때 main 이 가리키는 커밋을 찾아 쓰고 출력한다.
# 아직 비어 있다: 2026-10-28 실행 환경에서 huggingface.co 가 막혀 커밋을 확인하지 못했다(README 참고).
REVISION = None
CONTEXT = 512  # 입력 길이. 짧은 계열은 앞을 NaN 으로 채운다(공식 left_pad_and_stack_1D 와 같은 규칙)
QUANTILES = [0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9]
OPSET = 20  # torch 2.9 dynamo 내보내기의 기본값. 18 로 낮추는 변환은 실패한다. ORT 1.31 은 20 을 지원한다
LIMIT = 0.01  # 최대 상대 오차 1%
ROOT = Path(__file__).resolve().parents[2]
ASSET = ROOT / "car/app/src/main/assets/chronos_bolt_tiny.onnx"


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def load(model_dir, revision):
    """공식 파이프라인, 리비전, 가중치 파일 경로, 허브가 밝힌 가중치 SHA-256(로컬이면 None)을 돌려준다."""
    if model_dir:
        weights = Path(model_dir) / "model.safetensors"
        return ChronosBoltPipeline.from_pretrained(model_dir), "local:" + str(model_dir), weights, None
    from huggingface_hub import HfApi, hf_hub_download

    if revision is None:
        revision = HfApi().model_info(REPO, revision="main").sha
        print(f"REVISION 이 비어 있어 main 을 씀: {revision} (스크립트의 REVISION 에 넣어 고정)")
    info = HfApi().model_info(REPO, revision=revision, files_metadata=True)
    hub_sha = next(s.lfs.sha256 for s in info.siblings if s.rfilename == "model.safetensors")
    pipe = ChronosBoltPipeline.from_pretrained(REPO, revision=revision)
    return pipe, revision, Path(hf_hub_download(REPO, "model.safetensors", revision=revision)), hub_sha


def synthetic_series():
    """주간 트립 수 같은 양의 정수 계열 20개(길이 8~52). 6개는 끝 무렵 갑자기 절반으로 떨어진다."""
    rng = np.random.default_rng(20261027)
    lengths = [8, 10, 12, 13, 15, 16, 17, 20, 24, 26, 30, 32, 33, 36, 40, 44, 47, 48, 50, 52]
    kinds = ["steady", "halve", "trend", "small", "seasonal", "halve", "steady", "trend", "halve", "small",
             "seasonal", "steady", "halve", "trend", "small", "halve", "seasonal", "steady", "halve", "trend"]
    out = []
    for i, (n, kind) in enumerate(zip(lengths, kinds)):
        base = rng.uniform(4, 25)
        t = np.arange(n)
        lam = np.full(n, base)
        if kind == "trend":
            lam = base * (1 + rng.uniform(-0.3, 0.3) * t / n)
        elif kind == "seasonal":
            lam = base * (1 + 0.25 * np.sin(2 * np.pi * t / 4.3))
        elif kind == "small":
            lam = np.full(n, rng.uniform(2, 6))
        if kind == "halve":
            cut = n - int(rng.integers(1, min(5, n // 2) + 1))
            lam[cut:] *= 0.5
        y = np.maximum(rng.poisson(lam), 1)
        out.append((f"s{i + 1:02d}", kind, y.astype(np.float32)))
    return out


def pad(series, length=CONTEXT):
    """앞을 NaN 으로 채워 [len(series), length] float32 로 만든다. 더 긴 계열은 뒤쪽 length 개만 쓴다."""
    x = np.full((len(series), length), np.nan, dtype=np.float32)
    for i, s in enumerate(series):
        s = s[-length:]
        x[i, length - len(s):] = s
    return x


class NextStep(torch.nn.Module):
    """[batch, 512] → [batch, 9]. 공식 forward 의 quantile_preds[:, :, 0] (다음 1스텝)만 꺼낸다."""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, context):
        return self.model(context=context).quantile_preds[:, :, 0]


def export(pipe, path):
    model = pipe.model.eval()
    model.config.use_cache = False
    model.decoder.config.use_cache = False
    example = torch.from_numpy(pad([np.arange(1, 41, dtype=np.float32), np.ones(9, dtype=np.float32)]))
    # TorchScript 내보내기(dynamo=False)는 InstanceNorm 의 aten::nanmean 을 못 바꿔 dynamo 내보내기를 쓴다.
    # 상수 접기가 T5 상대 위치 버킷의 log(0)·-inf 변환을 건드려 numpy 경고를 내지만, 그 값은 Where 에서 버려진다.
    with torch.no_grad(), np.errstate(divide="ignore", invalid="ignore"):
        torch.onnx.export(
            NextStep(model),
            (example,),
            str(path),
            input_names=["context"],
            output_names=["quantiles"],
            dynamic_shapes=({0: torch.export.Dim("batch", min=1, max=256)},),
            opset_version=OPSET,
            dynamo=True,
            external_data=False,
            optimize=True,
        )
    onnx.checker.check_model(onnx.load(str(path)))


def session(path, threads=0):
    so = ort.SessionOptions()
    so.intra_op_num_threads = threads
    return ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])


def tensor_spec(sess):
    def one(v):
        return {"name": v.name, "shape": v.shape, "dtype": v.type}

    return {"inputs": [one(v) for v in sess.get_inputs()], "outputs": [one(v) for v in sess.get_outputs()]}


def compare(pipe, sess, data):
    rows = []
    for name, kind, y in data:
        with torch.no_grad():
            ref = pipe.predict(torch.from_numpy(y), prediction_length=1)[0, :, 0].numpy()
        got = sess.run(None, {"context": pad([y])})[0][0]
        rel = np.abs(got - ref) / np.maximum(np.abs(ref), 1e-6)
        rows.append({
            "name": name, "kind": kind, "length": len(y), "last": float(y[-1]),
            "torch_q10": float(ref[0]), "torch_q50": float(ref[4]), "torch_q90": float(ref[8]),
            "max_abs": float(np.abs(got - ref).max()), "max_rel": float(rel.max()),
            "torch": ref.tolist(), "onnx": got.tolist(),
        })
    return rows


def timing(pipe, path):
    """지표 7개(계열 7개, 각 길이 52) 예측 한 번. 세션 만들기와 첫 호출은 따로, 나머지는 50회 중앙값(ms)."""
    rng = np.random.default_rng(7)
    series = [np.maximum(rng.poisson(rng.uniform(3, 20), 52), 1).astype(np.float32) for _ in range(7)]
    x = pad(series)
    out = {}
    for threads in (1, 0):
        t0 = time.perf_counter()
        sess = session(path, threads)
        t1 = time.perf_counter()
        batch = sess.run(None, {"context": x})[0]
        t2 = time.perf_counter()
        runs = []
        for _ in range(50):
            s = time.perf_counter()
            sess.run(None, {"context": x})
            runs.append(time.perf_counter() - s)
        singles = []
        for _ in range(20):
            s = time.perf_counter()
            one = [sess.run(None, {"context": x[i:i + 1]})[0][0] for i in range(7)]
            singles.append(time.perf_counter() - s)
        key = "ort_1thread" if threads == 1 else "ort_default"
        out[key] = {
            "session_ms": (t1 - t0) * 1e3,
            "first_batch7_ms": (t2 - t1) * 1e3,
            "batch7_median_ms": statistics.median(runs) * 1e3,
            "seq7x1_median_ms": statistics.median(singles) * 1e3,
            "batch_vs_single_max_abs": float(np.abs(batch - np.stack(one)).max()),
        }
    torch_runs = []
    tensors = [torch.from_numpy(s) for s in series]
    with torch.no_grad():
        pipe.predict(tensors, prediction_length=1)
        for _ in range(20):
            s = time.perf_counter()
            pipe.predict(tensors, prediction_length=1)
            torch_runs.append(time.perf_counter() - s)
    out["torch_batch7_median_ms"] = statistics.median(torch_runs) * 1e3
    out["torch_threads"] = torch.get_num_threads()
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--model", help="config.json 과 model.safetensors 가 든 폴더(없으면 Hugging Face)")
    ap.add_argument("--revision", default=REVISION, help="Hugging Face 커밋 해시")
    ap.add_argument("--report", help="결과 JSON 을 쓸 경로")
    ap.add_argument("--no-asset", action="store_true", help="통과해도 assets 에 복사하지 않는다")
    args = ap.parse_args()

    torch.manual_seed(0)
    pipe, revision, weights, hub_sha = load(args.model, args.revision)
    assert pipe.quantiles == QUANTILES, pipe.quantiles
    weights_sha = sha256(weights)
    if hub_sha and weights_sha != hub_sha:
        sys.exit(f"가중치 SHA-256 불일치: 받은 {weights_sha}, 허브 {hub_sha}")
    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "chronos_bolt_tiny.onnx"
        export(pipe, path)
        sess = session(path)
        rows = compare(pipe, sess, synthetic_series())
        worst = max(r["max_rel"] for r in rows)
        passed = worst <= LIMIT
        report = {
            "repo": REPO, "revision": revision,
            "weights_sha256": weights_sha, "weights_sha256_hub": hub_sha,
            "onnx_sha256": sha256(path), "onnx_bytes": path.stat().st_size,
            "opset": OPSET, "versions": {
                "python": sys.version.split()[0], "chronos": chronos.__version__, "torch": torch.__version__,
                "transformers": transformers.__version__, "onnx": onnx.__version__,
                "onnxscript": onnxscript.__version__, "onnxruntime": ort.__version__, "numpy": np.__version__,
            },
            "tensors": tensor_spec(sess),
            "rows": rows, "max_rel": worst, "limit": LIMIT, "passed": passed,
            "timing": timing(pipe, path),
        }
        if passed and not args.no_asset:
            ASSET.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(path, ASSET)
            report["asset"] = str(ASSET.relative_to(ROOT))

    print("| 계열 | 종류 | 길이 | 마지막 값 | PyTorch q0.1 / q0.5 / q0.9 | 최대 절대 오차 | 최대 상대 오차 |")
    print("|---|---|---:|---:|---|---:|---:|")
    for r in rows:
        print(f"| {r['name']} | {r['kind']} | {r['length']} | {r['last']:.0f} | "
              f"{r['torch_q10']:.3f} / {r['torch_q50']:.3f} / {r['torch_q90']:.3f} | "
              f"{r['max_abs']:.2e} | {r['max_rel']:.2e} |")
    summary = {k: v for k, v in report.items() if k != "rows"}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"판정: {'통과' if passed else '실패'} (최대 상대 오차 {worst:.3e}, 기준 {LIMIT:.0%})")
    if args.report:
        Path(args.report).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
