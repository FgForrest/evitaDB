# Use an official Go runtime as a parent image
FROM golang:1.22.0-alpine3.19

# Set the working directory inside the container
WORKDIR /app

# Clone the repository
RUN go install github.com/CtrlSpice/otel-desktop-viewer@latest

# Run the application
EXPOSE 8000
EXPOSE 8888
EXPOSE 4317
EXPOSE 4318
ENTRYPOINT ["otel-desktop-viewer", "--host", "0.0.0.0"]
