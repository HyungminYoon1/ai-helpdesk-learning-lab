FROM python:3.11-slim-bookworm@sha256:0a310eeecf4e1f5a0743f9a6520c90c88d089c903ca5fd283f501e3a805f5f89

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PYTHONHASHSEED=0 \
    OMP_NUM_THREADS=1 OPENBLAS_NUM_THREADS=1 MKL_NUM_THREADS=1
WORKDIR /app
COPY requirements.txt ./
RUN python -m pip install --no-cache-dir --disable-pip-version-check --only-binary=:all: -r requirements.txt
COPY probe.py synthetic-cases.json README.md ./
USER 10001:10001
# A whole-experiment limit, NOT a per-Job scanner Adapter implementation.
ENTRYPOINT ["timeout", "--signal=TERM", "--kill-after=5s", "60s", "python", "/app/probe.py"]
