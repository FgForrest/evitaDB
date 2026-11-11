/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.test;

import io.evitadb.utils.Assert;
import io.evitadb.utils.CollectionUtils;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Optional.ofNullable;

/**
 * Port manager keeps track of ports used in {@link io.evitadb.server.EvitaServer} instances in unit tests.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class PortManager {
	/**
	 * System property selecting the first allocated port (default `5560`, inclusive range `1024..65000`).
	 * Parallel test runs on one machine should pass different bases to avoid overlapping port allocations.
	 * Read once when a manager is constructed.
	 */
	public static final String PORT_BASE_PROPERTY = "evita.test.portBase";

	private final int portBase;
	private final Random random = new Random(1L);
	private final ReentrantLock lock = new ReentrantLock();
	private final Map<String, int[]> portAllocationTable = CollectionUtils.createConcurrentHashMap(64);
	private final Set<Integer> allocatedPorts = new ConcurrentSkipListSet<>();
	private final Map<String, CompletableFuture<Void>> pendingReleases = CollectionUtils.createConcurrentHashMap(64);
	@Getter private int counter;
	@Getter private int peak;

	/**
	 * Creates a manager using the base port from {@link #PORT_BASE_PROPERTY}.
	 *
	 * @throws IllegalArgumentException if the property is not an integer within `1024..65000`
	 */
	public PortManager() {
		final String configuredBase = System.getProperty(PORT_BASE_PROPERTY, "5560");
		final String errorMessage = "System property `" + PORT_BASE_PROPERTY +
			"` must be an integer within 1024..65000 (inclusive), but was `" + configuredBase + "`.";
		try {
			this.portBase = Integer.parseInt(configuredBase);
		} catch (NumberFormatException ex) {
			throw new IllegalArgumentException(errorMessage, ex);
		}
		Assert.isTrue(this.portBase >= 1024 && this.portBase <= 65000, errorMessage);
	}

	/**
	 * Allocates a new set of ports for a particular `dataSetName`.
	 *
	 * @throws IllegalStateException if the requested ports exceed the available range through `65535`
	 */
	@Nonnull
	public int[] allocatePorts(@Nonnull String dataSetName, int count) {
		this.lock.lock();
		try {
			Assert.isPremiseValid(
				!this.portAllocationTable.containsKey(dataSetName),
				"Ports for dataset " + dataSetName + " already allocated."
			);

			int availablePorts = 65536 - this.portBase - this.allocatedPorts.size();
			for (Entry<String, CompletableFuture<Void>> entry : this.pendingReleases.entrySet()) {
				if (entry.getValue() != null && entry.getValue().isDone()) {
					availablePorts += this.portAllocationTable.get(entry.getKey()).length;
				}
			}
			if (count > availablePorts) {
				throw new IllegalStateException(
					"Cannot allocate " + count + " ports for dataset " + dataSetName +
						": only " + availablePorts + " ports remain in range " + this.portBase + "..65535."
				);
			}

			final Iterator<Entry<String, CompletableFuture<Void>>> it = this.pendingReleases.entrySet().iterator();
			while (it.hasNext()) {
				Entry<String, CompletableFuture<Void>> entry = it.next();
				final CompletableFuture<Void> future = entry.getValue();
				if (future != null && future.isDone()) {
					releasePorts(entry.getKey());
					it.remove();
				}
			}

			this.counter += count;
			this.peak = Math.max(this.peak, this.allocatedPorts.size());

			final int[] ports = new int[count];
			int index = 0;
			int port = this.portBase;
			while (index < count) {
				if (this.allocatedPorts.contains(port)) {
					port++;
				} else {
					final int portToAllocate = port++;
					ports[index++] = portToAllocate;
					this.allocatedPorts.add(portToAllocate);
				}
			}
			this.portAllocationTable.put(dataSetName, ports);
			return ports;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Frees all ports allocated for `dataSetName`.
	 */
	public void releasePorts(@Nonnull String dataSetName) {
		this.lock.lock();
		try {
			ofNullable(this.portAllocationTable.remove(dataSetName))
				.ifPresent(ports -> {
					for (int port : ports) {
						this.allocatedPorts.remove(port);
					}
				});
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Frees all ports when `whenCompleted` future is completed.
	 * @param dataSetName dataset name
	 * @param whenCompleted future that triggers port release
	 */
	public void releasePortsOnCompletion(@Nonnull String dataSetName, @Nonnull CompletableFuture<Void> whenCompleted) {
		this.lock.lock();
		try {
			final int random = this.random.nextInt();
			final String randomizedDataSetName = dataSetName + "_" + random;
			// rename port allocation table entry, so that we can avoid conflicts on reused dataSetName
			this.portAllocationTable.put(
				randomizedDataSetName, this.portAllocationTable.remove(dataSetName)
			);
			this.pendingReleases.put(randomizedDataSetName, whenCompleted);
		} finally {
			this.lock.unlock();
		}
	}

}
