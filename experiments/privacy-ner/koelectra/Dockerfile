FROM python:3.11-slim-bookworm@sha256:0a310eeecf4e1f5a0743f9a6520c90c88d089c903ca5fd283f501e3a805f5f89

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONHASHSEED=0 \
    OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1 \
    TOKENIZERS_PARALLELISM=false HF_HUB_DISABLE_TELEMETRY=1 DO_NOT_TRACK=1 \
    HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 USE_TF=0 USE_FLAX=0
WORKDIR /app
COPY koelectra/requirements.txt /app/requirements.txt
RUN python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: \
      --index-url https://download.pytorch.org/whl/cpu torch==2.10.0+cpu \
    && python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: -r requirements.txt
COPY koelectra/fetch_model.py koelectra/model-lock.json /app/
RUN python /app/fetch_model.py
COPY probe.py synthetic-cases.json /app/
COPY koelectra/probe_koelectra.py koelectra/test_probe_koelectra.py /app/
RUN python -m unittest -q test_probe_koelectra
RUN groupadd --gid 10001 privacy-probe \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin privacy-probe
USER 10001:10001
# Experiment-wide bound, not the production per-Job scan budget.
ENTRYPOINT ["timeout", "--signal=TERM", "--kill-after=5s", "180s", "python", "/app/probe_koelectra.py"]
