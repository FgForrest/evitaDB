/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.kryo;

import io.evitadb.core.metric.event.storage.ObservableOutputChangeEvent;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.concurrent.ThreadSafe;
import java.util.concurrent.locks.ReentrantLock;

/**
 * JVM-wide pool of exact-size frame buffers, shared by all engines and catalogs. Hard references retain at most
 * twice the available processor count in idle arrays across all sizes: one assembly array and one compression
 * array per concurrent frame writer. With a largest buffer size of S bytes, idle memory is at most
 * `2 × availableProcessors() × S` bytes, independent of the number of sizes ever requested. For one exact size,
 * idle arrays cannot outnumber peak simultaneous borrows, so disabling compression does not fill the extra slots.
 *
 * A fixed array of idle references replaces per-size buckets, bounding metadata as well as retained buffers.
 * Linear exact-size lookup and return allocate nothing after warm-up. When full, the oldest idle array is evicted
 * in favor of the returned array so changing the configured size can warm up without retaining obsolete sizes.
 * JFR events fire only on array allocation or eviction, never on reuse.
 *
 * Counters cover arrays this pool allocated and has not dropped, both borrowed and idle. An array its borrower
 * never returns stays counted even after garbage collection reclaims it, so the counters then overstate memory.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@ThreadSafe
public final class ObservableOutputBufferPool {
	/**
	 * Shared pool sized for one concurrent frame writer per processor in this JVM.
	 */
	public static final ObservableOutputBufferPool INSTANCE =
		new ObservableOutputBufferPool(Runtime.getRuntime().availableProcessors());
	/**
	 * Guards idle arrays, counters and event commits.
	 */
	private final ReentrantLock lock = new ReentrantLock();
	/**
	 * Fixed-capacity store of idle arrays, ordered from oldest to most recently returned.
	 */
	private final byte[][] idle;
	/**
	 * Number of occupied idle slots; later slots are null.
	 */
	private int idleCount;
	/**
	 * Number of borrowed and idle arrays together.
	 */
	private int openedBuffers;
	/**
	 * Total size of borrowed and idle arrays.
	 */
	private long occupiedMemoryBytes;

	/**
	 * Creates a pool sized for the expected concurrent frame writers. Each writer can borrow two arrays when
	 * compression is enabled. The shared instance uses the JVM's available processor count for concurrency.
	 *
	 * @param concurrency positive number of concurrent frame writers
	 * @throws io.evitadb.exception.GenericEvitaInternalError if concurrency is not positive
	 */
	public ObservableOutputBufferPool(int concurrency) {
		Assert.isPremiseValid(concurrency > 0, "Concurrency must be positive!");
		this.idle = new byte[Math.multiplyExact(2, concurrency)][];
	}

	/**
	 * Borrows the most recently returned array of exactly the requested size, or allocates one and reports it.
	 * The caller exclusively owns the array until it returns it with {@link #release(byte[])}.
	 * Allocation happens outside the pool lock so allocating a large array does not block other borrowers or
	 * releasers. Counters and their event are updated together under the lock only after allocation succeeds.
	 *
	 * @param size exact number of bytes required by the output
	 * @return an exclusively borrowed array of the requested size
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the requested size is not positive
	 */
	@Nonnull
	public byte[] borrow(int size) {
		Assert.isPremiseValid(size > 0, "Buffer size must be positive!");
		this.lock.lock();
		try {
			for (int i = this.idleCount - 1; i >= 0; i--) {
				if (this.idle[i].length == size) {
					final byte[] result = this.idle[i];
					System.arraycopy(this.idle, i + 1, this.idle, i, this.idleCount - i - 1);
					this.idle[--this.idleCount] = null;
					return result;
				}
			}
		} finally {
			this.lock.unlock();
		}
		final byte[] result = new byte[size];
		this.lock.lock();
		try {
			this.openedBuffers++;
			this.occupiedMemoryBytes += size;
			emitChangeEvent();
		} finally {
			this.lock.unlock();
		}
		return result;
	}

	/**
	 * Returns a borrowed array, evicting and reporting the oldest idle array when the global store is full.
	 * The caller must return each borrowed array exactly once and stop accessing it afterwards.
	 *
	 * @param buffer the array previously borrowed from this pool
	 */
	public void release(@Nonnull byte[] buffer) {
		this.lock.lock();
		try {
			if (this.idleCount == this.idle.length) {
				final byte[] dropped = this.idle[0];
				System.arraycopy(this.idle, 1, this.idle, 0, --this.idleCount);
				this.idle[this.idleCount] = null;
				this.openedBuffers--;
				this.occupiedMemoryBytes -= dropped.length;
				emitChangeEvent();
			}
			this.idle[this.idleCount++] = buffer;
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Reports coherent counts after allocating or dropping an array while holding the pool lock.
	 * Commit under the lock because MetricHandler sets Prometheus gauges from the last event in a RecordingStream
	 * ordered by commit time, so committing after unlocking could reverse two threads' counter update order and leave
	 * a gauge stale until the next allocation or drop.
	 */
	private void emitChangeEvent() {
		new ObservableOutputChangeEvent(this.openedBuffers, this.occupiedMemoryBytes).commit();
	}
}
