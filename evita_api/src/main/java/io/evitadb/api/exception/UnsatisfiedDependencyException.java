/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.exception;

import io.evitadb.exception.EvitaInternalError;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when evitaDB attempts to use a feature that requires an optional library dependency
 * that is not present on the classpath. evitaDB has a modular architecture where certain features are
 * only activated when their corresponding libraries are available.
 *
 * **Common missing dependencies:**
 *
 * - GraphQL Java library - required for GraphQL API endpoint
 * - gRPC libraries - required for gRPC API endpoint
 * - MinIO Java SDK - required for S3-compatible export operations
 * - Specific serialization libraries - required for certain data format support
 * - Observability libraries - required for OpenTelemetry tracing or Prometheus metrics
 *
 * This exception extends {@link EvitaInternalError} because it represents a deployment or configuration
 * issue rather than a runtime bug. The private message contains technical details (class names, Maven
 * coordinates) while the public message provides user-friendly guidance.
 *
 * **Resolution:**
 *
 * 1. Add the missing library to your project's dependencies (Maven/Gradle)
 * 2. Ensure the correct evitaDB bundle is used (e.g., `evita_server` includes all optional dependencies)
 * 3. Disable the feature that requires the missing dependency if it's not needed
 * 4. Check that the dependency version is compatible with the evitaDB version
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class UnsatisfiedDependencyException extends EvitaInternalError {
	@Serial private static final long serialVersionUID = -7691809070603406429L;

	/**
	 * Creates a new exception with separate private and public messages.
	 *
	 * @param privateMessage detailed technical message with class names and dependency coordinates
	 * @param publicMessage user-friendly message explaining which feature requires which library
	 */
	public UnsatisfiedDependencyException(
		@Nonnull String privateMessage,
		@Nonnull String publicMessage
	) {
		super(privateMessage, publicMessage);
	}

}
