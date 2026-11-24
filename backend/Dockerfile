FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    TVI_ENVIRONMENT=production \
    TVI_STORAGE_DIR=/data/videos \
    TVI_MAX_UPLOAD_MB=10240

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg libglib2.0-0 libgl1 util-linux \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --home-dir /app app \
    && mkdir -p /app /data/videos \
    && chown -R app:app /app /data

WORKDIR /app
COPY pyproject.toml README.md ./
COPY app ./app
COPY docker-entrypoint.sh /usr/local/bin/tvi-entrypoint
RUN chmod 0755 /usr/local/bin/tvi-entrypoint \
    && pip install --no-cache-dir .

EXPOSE 8000
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/tvi-entrypoint"]
