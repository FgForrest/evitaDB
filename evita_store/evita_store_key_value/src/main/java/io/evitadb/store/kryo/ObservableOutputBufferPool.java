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
import javax.annotation.Nullable;

/**
 * JVM-wide pool of exact-size frame buffers, shared by all engines and catalogs. Hard references retain at most
 * one idle buffer per CPU core for each size, giving idle memory a fixed, predictable ceiling based on CPU
 * concurrency. A fixed stack per size and primitive size lookup keep borrowing and releasing allocation-free
 * after warm-up. JFR events report only allocations and drops, when the retained array set changes.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class ObservableOutputBufferPool {
	/**
	 * Shared pool for every output in this JVM.
	 */
	public static final ObservableOutputBufferPool INSTANCE = new ObservableOutputBufferPool();
	/**
	 * Maximum number of idle arrays retained for each size.
	 */
	private final int idleCapacity = Runtime.getRuntime().availableProcessors();
	/**
	 * Exact-size stacks; the small set of sizes is searched without boxing.
	 */
	@Nullable private SizedBuffers sizes;
	/**
	 * Number of borrowed and idle arrays together.
	 */
	private int openedBuffers;
	/**
	 * Total size of borrowed and idle arrays.
	 */
	private long occupiedMemoryBytes;

	/**
	 * Creates the single shared pool.
	 */
	private ObservableOutputBufferPool() {
	}

	/**
	 * Borrows an idle array of exactly the requested size, or allocates one and reports the added memory.
	 * The caller exclusively owns the array until it returns it with {@link #release(byte[])}.
	 *
	 * @param size exact number of bytes required by the output
	 * @return an exclusively borrowed array of the requested size
	 * @throws io.evitadb.exception.GenericEvitaInternalError if the requested size is not positive
	 */
	@Nonnull
	public synchronized byte[] borrow(int size) {
		Assert.isPremiseValid(size > 0, "Buffer size must be positive!");
		final SizedBuffers sizedBuffers = getSizedBuffers(size);
		final byte[] result;
		if (sizedBuffers.count == 0) {
			result = new byte[size];
			this.openedBuffers++;
			this.occupiedMemoryBytes += size;
			emitChangeEvent();
		} else {
			result = sizedBuffers.idle[--sizedBuffers.count];
			sizedBuffers.idle[sizedBuffers.count] = null;
		}
		return result;
	}

	/**
	 * Returns a borrowed array, dropping it and reporting the removed memory when its exact-size idle stack is full.
	 * The caller must return each borrowed array exactly once and stop accessing it afterwards.
	 *
	 * @param buffer the array previously borrowed from this pool
	 */
	public synchronized void release(@Nonnull byte[] buffer) {
		final SizedBuffers sizedBuffers = getSizedBuffers(buffer.length);
		if (sizedBuffers.count < this.idleCapacity) {
			sizedBuffers.idle[sizedBuffers.count++] = buffer;
		} else {
			this.openedBuffers--;
			this.occupiedMemoryBytes -= buffer.length;
			emitChangeEvent();
		}
	}

	/**
	 * Finds or initializes the stack for an exact array size while holding the pool monitor.
	 *
	 * @param size exact array size to look up
	 * @return the stack retaining idle arrays of that size
	 */
	@Nonnull
	private SizedBuffers getSizedBuffers(int size) {
		for (SizedBuffers current = this.sizes; current != null; current = current.next) {
			if (current.size == size) {
				return current;
			}
		}
		this.sizes = new SizedBuffers(size, this.idleCapacity, this.sizes);
		return this.sizes;
	}

	/**
	 * Reports coherent JVM-wide counts after allocating or dropping an array while holding the pool monitor.
	 */
	private void emitChangeEvent() {
		new ObservableOutputChangeEvent(this.openedBuffers, this.occupiedMemoryBytes).commit();
	}

	/**
	 * Fixed stack of idle arrays of one exact size. Access is guarded by the pool monitor.
	 */
	private static final class SizedBuffers {
		/**
		 * Exact size of every array retained by this stack.
		 */
		private final int size;
		/**
		 * Fixed-capacity stack of returned arrays; entries beyond {@link #count} are null.
		 */
		private final byte[][] idle;
		/**
		 * Next exact-size stack in the pool, or null for the last size.
		 */
		@Nullable private final SizedBuffers next;
		/**
		 * Number of idle arrays currently retained in this stack.
		 */
		private int count;

		/**
		 * Initializes one size's fixed stack, linked to the previously registered sizes.
		 *
		 * @param size exact size of arrays held by this stack
		 * @param capacity maximum number of idle arrays to retain
		 * @param next next size stack, or null when this is the first registered size
		 */
		private SizedBuffers(int size, int capacity, @Nullable SizedBuffers next) {
			this.size = size;
			this.idle = new byte[capacity][];
			this.next = next;
		}
	}
}
