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

import io.evitadb.cluster.mock.configuration.MockClusterOptions;
import io.evitadb.spi.cluster.EnvironmentService;
import io.evitadb.spi.cluster.model.ClusterEnvironment;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Mock implementation of {@link EnvironmentService} for testing purposes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class MockEnvironmentService implements EnvironmentService {
	private final MockClusterOptions options;

	public MockEnvironmentService(MockClusterOptions options) {
		this.options = options;
	}

	@Nonnull
	@Override
	public ClusterEnvironment getEnvironment() {
		try {
			// Basic mock environment
			List<InetAddress> nodes = new ArrayList<>();
			for (int i = 0; i < options.getClusterSize(); i++) {
				nodes.add(InetAddress.getByName("127.0.0." + (i + 1)));
			}
			return new ClusterEnvironment(
				nodes.toArray(new InetAddress[0]),
				0 // this replica index
			);
		} catch (UnknownHostException e) {
			throw new RuntimeException(e);
		}
	}

	@Override
	public boolean claimLeadership() {
		return true; // Always succeed in mock
	}

	@Override
	public boolean maintainLeadership() {
		return true; // Always succeed in mock
	}

	@Override
	public void resignLeadership() {
		// Do nothing
	}
}
