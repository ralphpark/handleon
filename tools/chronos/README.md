# Chronos-Bolt-Tiny ONNX 관문

설계 A2(시계열 AI 예측)의 첫날 관문입니다. `amazon/chronos-bolt-tiny` 를 ONNX 로 내보내 차 안의 ONNX Runtime 으로 돌렸을 때,
공식 파이프라인(PyTorch)과 다음 1스텝(1주) 분위수 9개가 **모든 계열·분위수에서 최대 상대 오차 1% 이하**인지 봅니다.

## 판정: 미판정 (2026-10-28)

이 작업을 돌린 클라우드 환경의 네트워크 정책이 `huggingface.co` 를 막아(프록시 403) 가중치를 받지 못했습니다.
그래서 실제 가중치로는 내보내기·비교·시간 재기를 하지 못했고, `car/app/src/main/assets/chronos_bolt_tiny.onnx` 도 넣지 않았습니다.
다른 공식 경로도 확인했지만 쓸 수 없었습니다. SageMaker JumpStart 공개 목록에는 Chronos-Bolt base·small 만 있고 tiny 는 없으며,
AutoGluon S3 버킷은 목록을 볼 수 없습니다.

스크립트는 준비돼 있습니다. `huggingface.co` 가 열리면 아래 [다시 돌리기](#다시-돌리기) 한 줄로 판정이 나옵니다.

| 항목 | 값 |
|---|---|
| 고정 리비전 | **미확인**. Hugging Face MCP 로 파일 목록만 봄: `config.json` 1,120 B, `model.safetensors` 34,622,352 B(LFS), `README.md` 8,659 B. 커밋 해시와 LFS SHA-256 은 이 경로로 나오지 않음 |
| 가중치 SHA-256 | 미확인 (스크립트가 받은 파일의 해시를 허브의 LFS SHA-256 과 대조하고, 다르면 멈춤) |
| ONNX 파일 SHA-256 | 미확인 (만들지 못함) |
| 최대 상대 오차 | 미측정 |
| 지표 7개 예측 시간 | 미측정 |

## 입력·출력 텐서

감싸개(`NextStep`)와 내보내기 설정으로 정해지는 규격입니다. 같은 구조의 공식 테스트용 더미 모델로 내보내 ORT 에서 확인했습니다.

| | 이름 | 모양 | dtype |
|---|---|---|---|
| 입력 | `context` | `[batch, 512]` | float32 |
| 출력 | `quantiles` | `[batch, 9]` | float32 |

- 출력 열 순서는 분위수 0.1, 0.2, …, 0.9 입니다. 80% 예측 구간은 `quantiles[:, 0]` ~ `quantiles[:, 8]` 입니다.
- `batch` 는 1~256 사이에서 바꿀 수 있습니다. 지표 7개는 `[7, 512]` 로 한 번에 넣어도 되고, 한 줄씩 넣어도 결과가 같습니다.
- 공식 `ChronosBoltModelForForecasting.forward` 의 `quantile_preds`(`[batch, 9, 64]`) 중 첫 스텝 `[:, :, 0]` 만 꺼냅니다.
  가중치와 모델 코드는 손대지 않았습니다.
- opset 은 `ai.onnx` 20 입니다(torch 2.9 dynamo 내보내기의 기본값). ONNX Runtime 1.31 이 지원하는 범위입니다.

### NaN 채우기 규칙

공식 `chronos.utils.left_pad_and_stack_1D` 와 같습니다.

1. 과거 값을 오래된 것부터 최근 것 순서로 둡니다.
2. 512 개보다 짧으면 **앞(왼쪽)을 NaN 으로 채워** 마지막 값이 `context[i, 511]` 에 오게 합니다. 512 개보다 길면 최근 512 개만 씁니다.
3. 값이 없는 주도 NaN 으로 넣을 수 있습니다. 모델이 NaN 을 결측으로 보고 평균·표준편차(nanmean)와 패치 마스크에서 뺍니다.

512 는 패치 크기 16 의 배수라서, 원래 길이 그대로 넣었을 때와 패치 경계가 같습니다. 전부 NaN 인 패치는 attention 에서 가려집니다.
그래서 512 로 채운 ONNX 출력이 원래 길이로 넣은 공식 파이프라인 출력과 같아야 하고, 아래 비교가 이것까지 함께 확인합니다.

## 비교 방법

- 합성 계열 20개(시드 20261027): 길이 8, 10, 12, 13, 15, 16, 17, 20, 24, 26, 30, 32, 33, 36, 40, 44, 47, 48, 50, 52.
  주간 트립 수 같은 포아송 정수(1 이상)이고, 종류는 평탄 4 · 추세 4 · 작은 값 3 · 주기 3 · **끝 무렵 갑자기 절반** 6 입니다.
  패치 경계 근처(15·16·17, 31·32·33, 47·48)를 일부러 넣었습니다.
- PyTorch: `ChronosBoltPipeline.predict(계열, prediction_length=1)`. 채우지 않은 원래 길이로 넣습니다.
- ONNX: 512 로 NaN 채움, `onnxruntime` 1.31.0 `CPUExecutionProvider`, 기본 그래프 최적화.
- 상대 오차 = `|ONNX − PyTorch| / max(|PyTorch|, 1e-6)`. 계열마다 분위수 9개 중 최댓값을 표에 적고, 20개 전체 최댓값이 1% 이하면 통과입니다.
- 시간: 계열 7개(각 길이 52)를 `[7, 512]` 한 번으로 예측. 세션 만들기와 첫 호출은 따로 적고, 나머지는 50회 중앙값입니다.
  1스레드와 기본 스레드를 모두 재고, 참고로 PyTorch 시간도 적습니다.

### 비교 표

미실행 (가중치를 받지 못함).

### 걸린 시간

미실행 (가중치를 받지 못함).

## 스크립트 점검 (판정 아님)

실제 가중치 대신 공식 저장소에 들어 있는 테스트용 더미 모델
`test/dummy-chronos-bolt-model`(같은 `ChronosBoltModelForForecasting` 구조, d_model 8)로 스크립트를 끝까지 돌렸습니다.
20개 계열의 최대 상대 오차는 8.6e-08 이었습니다. nanmean 정규화, 패치 나누기, T5 상대 위치 편향, [REG] 토큰, NaN 채우기가
ONNX 로 옮겨져 같은 값을 낸다는 점은 확인했지만, 이 수치가 실제 가중치의 판정을 대신하지는 않습니다.

내보내면서 만난 문제와 고친 방법입니다.

1. TorchScript 내보내기(`dynamo=False`)는 `aten::nanmean` 을 지원하지 않습니다. dynamo 내보내기로 바꿨습니다.
2. dynamo 내보내기는 opset 20 으로 그래프를 만들고, 18 로 낮추는 변환은 실패했습니다. opset 20 을 그대로 씁니다.
3. onnxscript 0.5.x 는 dtype 을 적지 않은 `aten::full`(정수 채움)을 float 로 바꿔, [REG] 토큰 임베딩의 `Gather` 인덱스가 float 가 되어
   ORT 가 모델을 거부했습니다. 0.7.2(onnx-ir 1.0.0)에서 고쳐졌습니다.

## 다시 돌리기

1. 클라우드 환경이라면 환경 설정의 Network access 에서 `huggingface.co` 와 내려받기 호스트(`*.huggingface.co`, `*.hf.co`)를 허용합니다.
   (https://code.claude.com/docs/en/cloud-environments#network-access)
2. 의존성을 설치합니다(Python 3.13, x86_64). PyPI 의 Linux torch 는 CUDA 라이브러리(약 3GB)를 함께 받습니다.

   ```
   python3 -m venv .venv
   .venv/bin/pip install chronos-forecasting==2.3.2 torch==2.9.1 transformers==4.57.3 accelerate==1.12.0 \
       huggingface_hub==0.36.0 onnx==1.19.1 onnxscript==0.7.2 onnx-ir==1.0.0 onnxruntime==1.31.0 numpy==2.3.5
   ```

3. 저장소 루트에서 돌립니다. 통과하면 ONNX 를 `car/app/src/main/assets/chronos_bolt_tiny.onnx` 에 두고 종료 코드 0, 실패하면 넣지 않고 1 입니다.

   ```
   .venv/bin/python tools/chronos/export_and_check.py --report chronos_report.json
   ```

   미리 받아 둔 폴더를 쓰려면 `--model DIR` 을 줍니다. 이때는 허브와 대조할 해시가 없으니 `model.safetensors` 의 SHA-256 을 직접 확인합니다.

4. 출력에 나온 커밋 해시를 스크립트의 `REVISION` 에 넣고, 표·해시·시간·판정을 이 문서에 옮깁니다.
   합성 계열은 시드가 고정이라 매번 같고, 시간은 기계마다 다릅니다.
