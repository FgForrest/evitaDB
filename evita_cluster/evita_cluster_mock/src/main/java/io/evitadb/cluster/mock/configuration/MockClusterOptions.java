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

package io.evitadb.cluster.mock.configuration;

import io.evitadb.api.configuration.ClusterOptions;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for the mock cluster service implementation.
 * This class extends {@link ClusterOptions} and adds mock-specific settings.
 *
 * The mock cluster implementation is used for development and testing purposes,
 * simulating a cluster environment on a single node.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ToString
public class MockClusterOptions extends ClusterOptions {

	/**
	 * Implementation code used to identify this cluster service type.
	 */
	public static final String IMPLEMENTATION_CODE = "mock";

	/**
	 * Default cluster size for the mock implementation.
	 */
	public static final int DEFAULT_CLUSTER_SIZE = 1;

	/**
	 * The simulated cluster size (number of nodes) for the mock cluster.
	 * This is primarily used for testing cluster behavior without deploying multiple nodes.
	 */
	@Getter
	@Setter
	private int clusterSize;

	/**
	 * Default constructor with default values.
	 */
	public MockClusterOptions() {
		super();
		this.clusterSize = DEFAULT_CLUSTER_SIZE;
	}

	/**
	 * Constructor with all parameters.
	 *
	 * @param enabled     indicates whether this cluster implementation is enabled
	 * @param clusterSize the simulated cluster size
	 */
	public MockClusterOptions(
		@Nullable Boolean enabled,
		int clusterSize
	) {
		super(enabled);
		this.clusterSize = clusterSize > 0 ? clusterSize : DEFAULT_CLUSTER_SIZE;
	}

	@Nonnull
	@Override
	public String getImplementationCode() {
		return IMPLEMENTATION_CODE;
	}

	/**
	 * Builder for the mock cluster options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Builder for the mock cluster options.
	 * Recommended to use to avoid binary compatibility problems in the future.
	 */
	@Nonnull
	public static Builder builder(@Nonnull MockClusterOptions options) {
		return new Builder(options);
	}

	/**
	 * Standard builder pattern implementation.
	 */
	@ToString
	public static class Builder {
		@Nullable private Boolean enabled = null;
		private int clusterSize = DEFAULT_CLUSTER_SIZE;

		Builder() {
		}

		Builder(@Nonnull MockClusterOptions options) {
			this.enabled = options.getEnabled();
			this.clusterSize = options.getClusterSize();
		}

		@Nonnull
		public Builder enabled(@Nullable Boolean enabled) {
			this.enabled = enabled;
			return this;
		}

		@Nonnull
		public Builder clusterSize(int clusterSize) {
			this.clusterSize = clusterSize;
			return this;
		}

		@Nonnull
		public MockClusterOptions build() {
			return new MockClusterOptions(this.enabled, this.clusterSize);
		}
	}

}
