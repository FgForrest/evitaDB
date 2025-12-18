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

package io.evitadb.cluster.k8s;

import io.evitadb.cluster.k8s.configuration.K8SClusterOptions;
import io.evitadb.spi.cluster.EnvironmentService;
import io.evitadb.spi.cluster.model.ClusterEnvironment;

import javax.annotation.Nonnull;

/**
 * Kubernetes implementation of {@link EnvironmentService}.
 * Currently a stub.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class K8SEnvironmentService implements EnvironmentService {
	private final K8SClusterOptions options;

	public K8SEnvironmentService(K8SClusterOptions options) {
		this.options = options;
	}

	@Nonnull
	@Override
	public ClusterEnvironment getEnvironment() {
		throw new UnsupportedOperationException("K8S cluster not implemented yet");
	}

	@Override
	public boolean claimLeadership() {
		throw new UnsupportedOperationException("K8S cluster not implemented yet");
	}

	@Override
	public boolean maintainLeadership() {
		throw new UnsupportedOperationException("K8S cluster not implemented yet");
	}

	@Override
	public void resignLeadership() {
		throw new UnsupportedOperationException("K8S cluster not implemented yet");
	}
}
