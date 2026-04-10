# Setup Environment Data

## Project Info
- **Language**: Java 17 (OpenJDK 17)
- **Build Tool**: Maven 3.8.8+
- **Project Structure**: Multi-module Maven project.
- **Root Directory**: `/www/oss/evitaDB`

## Dependencies
- `evita_engine` (Core module)
- `evita_cluster/evita_cluster_k8s` (New module, depends on `evita_engine`)
- `evita_cluster/evita_cluster_mock` (New module, depends on `evita_engine`)

## Setup Instructions
1.  Ensure Java 17 and Maven are installed.
2.  Run `mvn clean install -DskipTests` to build the project.
    - Note: Running full tests might take a long time. Use `-DskipTests` for quick build.

## Test Instructions
- Run all tests: `mvn test`
- Run specific module tests: `mvn test -pl <module-name>`
- Example: `mvn test -pl evita_cluster/evita_cluster_k8s`

## Issues Resolved
- Setup new Maven modules `evita_cluster/evita_cluster_k8s` and `evita_cluster/evita_cluster_mock`.
- Verified modules are recognized and dependencies resolve.
