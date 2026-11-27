# Projector bridge: Python + Google's adb (needs adb 30+ for wireless pairing)
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
 && curl -fsSL -o /tmp/pt.zip https://dl.google.com/android/repository/platform-tools-latest-linux.zip \
 && unzip -q /tmp/pt.zip -d /opt && ln -s /opt/platform-tools/adb /usr/local/bin/adb \
 && rm -rf /tmp/pt.zip /var/lib/apt/lists/* \
 && apt-get purge -y curl unzip && apt-get autoremove -y
COPY projector.py /app/projector.py
CMD ["python", "-u", "/app/projector.py"]
