FROM pytorch/pytorch:2.1.2-cuda12.1-cudnn8-runtime

ARG ORIENTERNET_COMMIT=0280b6e248046dc7d714801d7d0651a162fd5a63
ENV DEBIAN_FRONTEND=noninteractive \
    PYTHONUNBUFFERED=1 \
    ORIENTERNET_CACHE=/cache

RUN apt-get update && apt-get install -y --no-install-recommends \
    git libgl1 libglib2.0-0 libspatialindex-dev && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt
COPY constraints.txt /service/constraints.txt
RUN git clone https://github.com/facebookresearch/OrienterNet.git && \
    cd OrienterNet && git checkout ${ORIENTERNET_COMMIT} && \
    pip install --no-cache-dir -c /service/constraints.txt -r requirements/demo.txt && \
    pip install --no-cache-dir -e .

COPY requirements.txt /service/requirements.txt
RUN pip install --no-cache-dir -r /service/requirements.txt
COPY app /service/app
WORKDIR /service

EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health')"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
