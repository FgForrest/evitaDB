/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.core.exception;

import io.evitadb.api.configuration.ClusterOptions;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.io.Serial;

/**
 * Exception thrown when a cluster environment service implementation with the specified implementation code
 * cannot be found on the classpath.
 *
 * This exception is raised during cluster initialization when the {@link ClusterOptions} specify an
 * implementation code (via {@link ClusterOptions#getImplementationCode()}) that doesn't match any
 * {@link io.evitadb.spi.cluster.EnvironmentServiceFactory} discovered via the {@link java.util.ServiceLoader}
 * mechanism.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ClusterEnvironmentNotFoundException extends EvitaInvalidUsageException {
	@Serial private static final long serialVersionUID = 686648774436024049L;

	public ClusterEnvironmentNotFoundException(
		@Nonnull String serviceName,
		@Nonnull String implementationCode
	) {
		super(
			serviceName + " service with implementation code `" + implementationCode + "` was not found! " +
				"Make sure you have the correct implementation on the classpath!",
			serviceName + " service with implementation code was not found!"
		);
	}

}
