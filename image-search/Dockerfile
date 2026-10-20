FROM python:3.11-slim AS base

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    HF_HOME=/models \
    TORCH_HOME=/models/torch \
    OMP_NUM_THREADS=2 \
    MKL_NUM_THREADS=2

WORKDIR /app

COPY wheelhouse/torch-2.5.1+cpu-cp311-cp311-linux_x86_64.whl /tmp/torch-2.5.1+cpu-cp311-cp311-linux_x86_64.whl
RUN pip install --no-cache-dir --timeout 300 --retries 10 --index-url https://download.pytorch.org/whl/cpu /tmp/torch-2.5.1+cpu-cp311-cp311-linux_x86_64.whl torchvision==0.20.1

COPY requirements.txt .
RUN pip install --no-cache-dir --timeout 300 --retries 10 -r requirements.txt
COPY app ./app

FROM base AS test
COPY requirements-dev.txt .
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY tests ./tests
CMD ["pytest", "-q"]

FROM base AS runtime
RUN useradd --system --uid 10001 --create-home search && mkdir -p /data /models /backups && chown -R search:search /data /models /backups /app
USER search
EXPOSE 8090
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090", "--workers", "1"]
