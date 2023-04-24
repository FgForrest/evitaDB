/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.test;

import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Map;
import java.util.Set;

import static java.util.Optional.ofNullable;

/**
 * Port manager keeps track of ports used in {@link io.evitadb.server.EvitaServer} instances in unit tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PortManager {
	private final Object monitor = new Object();
	private final Map<String, int[]> portAllocationTable = CollectionUtils.createHashMap(64);
	private final Set<Integer> allocatedPorts = CollectionUtils.createLinkedHashSet(64);
	@Getter private int counter;
	@Getter private int peak;

	/**
	 * Allocates new set of port for particular `dataSetName`.
	 */
	@Nonnull
	public int[] allocatePorts(@Nonnull String dataSetName, int count) {
		synchronized (monitor) {
			this.counter += count;
			this.peak = Math.max(this.peak, this.allocatedPorts.size());

			final int[] ports = new int[count];
			int index = 0;
			int port = 5555;
			while (index < count) {
				if (allocatedPorts.contains(port)) {
					port++;
				} else {
					final int portToAllocate = port++;
					ports[index++] = portToAllocate;
					allocatedPorts.add(portToAllocate);
				}
			}
			portAllocationTable.put(dataSetName, ports);
			return ports;
		}
	}

	/**
	 * Frees all ports allocated for `dataSetName`.
	 */
	public void releasePorts(@Nonnull String dataSetName) {
		synchronized (monitor) {
			ofNullable(portAllocationTable.remove(dataSetName))
				.ifPresent(ports -> {
					for (int port : ports) {
						allocatedPorts.remove(port);
					}
				});
		}
	}

}
