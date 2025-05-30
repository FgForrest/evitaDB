/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.store.wal.supplier;

import io.evitadb.api.requestResponse.transaction.TransactionMutation;
import io.evitadb.dataType.array.CompositeObjectArray;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * This class represents a collection of transaction locations. It maintains a full known
 * transaction location history and provides methods to interact with the locations.
 */
public class TransactionLocations {
	/**
	 * Lock is used for synchronization of the access to non-thread-safe {@link #locations}.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * The time of the last read of the transaction locations.
	 */
	@Getter private long lastReadTime = System.currentTimeMillis();
	/**
	 * The full known transaction location history maintained in this object.
	 */
	@Nullable private CompositeObjectArray<TransactionLocation> locations = new CompositeObjectArray<>(TransactionLocation.class, true);
	/**
	 * The last known transaction location maintained in this object.
	 */
	@Nullable private TransactionLocation lastLocation;

	/**
	 * Returns the number of transaction locations maintained in this object.
	 *
	 * @return the number of transaction locations, or -1 if the lock could not be acquired
	 */
	public int size() {
		if (this.lock.tryLock()) {
			try {
				return this.locations == null ? 1 : this.locations.getSize();
			} finally {
				this.lock.unlock();
			}
		} else {
			return -1;
		}
	}

	/**
	 * Releases all transaction locations maintained in this object and leaves only the last one.
	 * Throws an exception if the locations are already cut.
	 *
	 * @return true if the locations were successfully cut, false if the lock could not be acquired
	 */
	public boolean cut() {
		Assert.isPremiseValid(this.locations != null, "The locations are already cut!");
		try {
			if (this.lock.tryLock(100, MILLISECONDS)) {
				try {
					this.lastLocation = this.locations.getLast();
					this.locations = null;
					return true;
				} finally {
					this.lock.unlock();
				}
			}
		} catch (InterruptedException ignored) {
			// do nothing
			Thread.currentThread().interrupt();
		}
		return false;
	}

	/**
	 * Registers a transaction mutation with the given start position in the file.
	 *
	 * @param startPosition       the total value before the transaction mutation
	 * @param transactionMutation the transaction mutation to register
	 */
	public void register(long startPosition, @Nonnull TransactionMutation transactionMutation) {
		notifyAboutUsage();
		if (this.locations == null) {
			this.locations = new CompositeObjectArray<>(TransactionLocation.class);
		}
		if (this.lock.tryLock()) {
			try {
				final boolean canBeAppended = ofNullable(this.locations.getLast())
					.map(it -> it.catalogVersion() + 1 == transactionMutation.getVersion())
					.orElse(true);
				if (canBeAppended) {
					this.locations.add(
						new TransactionLocation(
							transactionMutation.getVersion(),
							startPosition,
							transactionMutation.getMutationCount(),
							transactionMutation.getWalSizeInBytes()
						)
					);
				}
			} finally {
				this.lock.unlock();
			}
		}
	}

	/**
	 * Finds the nearest location in the catalog based on the given catalog version.
	 *
	 * @param catalogVersion the catalog version to find the nearest location for
	 * @return the start position of the nearest location, or 0 if no location is found
	 */
	public long findNearestLocation(long catalogVersion) {
		notifyAboutUsage();
		final CompositeObjectArray<TransactionLocation> locs = this.locations;
		if (locs != null) {
			if (this.lock.tryLock()) {
				try {
					final int index = locs.indexOf(
						catalogVersion,
						(transactionLocation, cv) -> Long.compare(transactionLocation.catalogVersion(), cv)
					);
					return index >= 0 ?
						Objects.requireNonNull(locs.get(index)).startPosition() :
						0L;
				} finally {
					this.lock.unlock();
				}
			}
		} else {
			final TransactionLocation ll = this.lastLocation;
			if (ll != null && ll.catalogVersion() <= catalogVersion) {
				return ll.startPosition();
			}
		}
		return 0L;
	}

	/**
	 * Notifies about the usage of a transaction location object by updating the last read time.
	 */
	private void notifyAboutUsage() {
		this.lastReadTime = System.currentTimeMillis();
	}

}
