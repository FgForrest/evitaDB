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

package io.evitadb.cluster.k8s.configuration;

import io.evitadb.api.configuration.ClusterOptions;
import lombok.ToString;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Configuration options for the Kubernetes cluster service implementation.
 * This class extends {@link ClusterOptions} and will add K8S-specific settings.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@ToString
public class K8SClusterOptions extends ClusterOptions {

	/**
	 * Implementation code used to identify this cluster service type.
	 */
	public static final String IMPLEMENTATION_CODE = "k8s";

	/**
	 * Default constructor with default values.
	 */
	public K8SClusterOptions() {
		super();
	}

	/**
	 * Constructor with enabled parameter.
	 *
	 * @param enabled indicates whether this cluster implementation is enabled
	 */
	public K8SClusterOptions(@Nullable Boolean enabled) {
		super(enabled);
	}

	@Nonnull
	@Override
	public String getImplementationCode() {
		return IMPLEMENTATION_CODE;
	}

}
