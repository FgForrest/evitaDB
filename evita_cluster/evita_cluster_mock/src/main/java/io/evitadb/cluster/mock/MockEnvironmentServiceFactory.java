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

package io.evitadb.cluster.mock;

import io.evitadb.api.configuration.ClusterOptions;
import io.evitadb.cluster.mock.configuration.MockClusterOptions;
import io.evitadb.spi.cluster.EnvironmentService;
import io.evitadb.spi.cluster.EnvironmentServiceFactory;

import javax.annotation.Nonnull;

/**
 * Factory for creating mock environment service.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MockEnvironmentServiceFactory implements EnvironmentServiceFactory {

	@Nonnull
	@Override
	public String getImplementationCode() {
		return MockClusterOptions.IMPLEMENTATION_CODE;
	}

	@Nonnull
	@Override
	public Class<? extends ClusterOptions> getConfigurationClass() {
		return MockClusterOptions.class;
	}

	@Nonnull
	@Override
	public EnvironmentService create(@Nonnull ClusterOptions clusterOptions) {
		if (!(clusterOptions instanceof MockClusterOptions)) {
			throw new IllegalArgumentException("Expected MockClusterOptions but got " + clusterOptions.getClass().getName());
		}
		return new MockEnvironmentService((MockClusterOptions) clusterOptions);
	}

}
