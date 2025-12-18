/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.spi.cluster;

import io.evitadb.api.configuration.ClusterOptions;

import javax.annotation.Nonnull;
import java.util.ServiceLoader;

/**
 * This interface and layer of abstraction was introduced because we want to have different implementations of
 * a ViewStamped Replication service - namely mock and gRPC-based. Therefore, we used {@link ServiceLoader} pattern
 * to dynamically locate proper implementation of this interface and link these modules in runtime.
 *
 * The VSR service itself also needs initial configuration from the main evitaDB class, and therefore we need
 * this factory to pass the configuration from the main module into the cluster module.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface ViewStampedReplicationServiceFactory {

	/**
	 * Returns unique implementation code that identifies this VSR service type.
	 * This code must match the implementation code returned by the corresponding
	 * {@link ClusterOptions#getImplementationCode()}.
	 *
	 * @return implementation code (e.g., "mock" or "k8s")
	 */
	@Nonnull
	String getImplementationCode();

	/**
	 * Returns the configuration class for this VSR service type.
	 * Used for dynamic YAML deserialization via Jackson.
	 *
	 * @return configuration class extending {@link ClusterOptions}
	 */
	@Nonnull
	Class<? extends ClusterOptions> getConfigurationClass();

	/**
	 * Returns priority for default selection when no implementation is explicitly enabled.
	 * Higher value means higher priority. Mock implementation should return higher
	 * priority to be the default choice.
	 *
	 * @return priority value (higher = more preferred as default)
	 */
	default int getPriority() {
		return 0;
	}

	/**
	 * Creates default configuration instance with sensible defaults.
	 * Used when no configuration is provided in YAML for this implementation.
	 *
	 * @return default configuration options
	 */
	@Nonnull
	ClusterOptions createDefaultOptions();

	/**
	 * Creates new instance of {@link ViewStampedReplicationService}.
	 *
	 * @param clusterOptions cluster configuration options
	 * @return configured VSR service instance
	 */
	@Nonnull
	ViewStampedReplicationService create(@Nonnull ClusterOptions clusterOptions);

}
