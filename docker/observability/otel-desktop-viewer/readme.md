# Run OTEL Desktop viewer locally in docker

## Setup viewer

Run docker container:
```bash
docker-compose up
```

The viewer will listen on port 4317 for gRPC requests and on port 4318 for HTTP requests (doesn't work currently).
Then open browser and navigate to `http://localhost:8000` to view the OTEL Desktop viewer.

## Setup evita server

To enable evita server to send traces to the viewer, you need to set the following tracing config into evita configuration:
```yaml
tracing:
    endpoint: http://localhost:4317
    protocol: grpc
```