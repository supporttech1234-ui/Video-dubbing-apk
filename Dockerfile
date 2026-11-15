FROM python:3.11-slim

RUN apt-get update && \
    apt-get install -y ffmpeg git cmake build-essential && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

RUN git clone --depth 1 https://github.com/ggerganov/whisper.cpp.git /app/whisper.cpp && \
    cmake -B /app/whisper.cpp/build /app/whisper.cpp && \
    cmake --build /app/whisper.cpp/build -j2 --config Release

COPY . .

EXPOSE 8081

CMD ["python", "backend.py"]
