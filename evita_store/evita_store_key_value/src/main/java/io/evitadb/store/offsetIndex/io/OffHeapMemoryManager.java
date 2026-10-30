/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.store.offsetIndex.io;


import io.evitadb.core.metric.event.transaction.OffHeapMemoryAllocationChangeEvent;
import io.evitadb.store.checksum.ChecksumFactory;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.OutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.ReentrantLock;

/**
 * OffHeapMemoryManager class is responsible for managing off-heap memory regions and providing
 * free regions to acquire OutputStreams for writing data.
 *
 * The memory block is allocated when the first region is acquired, so an engine or a catalog that never runs
 * a transaction holds no off-heap memory. It is freed explicitly, where the JDK allows it, once the manager is closed
 * and every region has been released: the garbage collector would release it only when it collects the buffer object,
 * which may happen long after the catalog closed. A region still in use when the manager closes keeps the whole block
 * allocated - a WAL reference may still be copying the region's slice - and the region released last frees it.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Slf4j
public class OffHeapMemoryManager implements Closeable {
	/**
	 * Frees the memory of a direct buffer at once - `sun.misc.Unsafe#invokeCleaner` bound to the `Unsafe` instance -
	 * or `null` when this JDK offers no such method, in which case the buffer is left to the garbage collector.
	 */
	@Nullable private static final MethodHandle INVOKE_CLEANER = lookupInvokeCleaner();
	/**
	 * Factory for creating checksums for off-heap memory output streams.
	 * Each {@link OffHeapMemoryOutputStream} acquired from this manager receives a checksum instance
	 * created by this factory for data integrity verification.
	 * Sourced from {@link io.evitadb.api.configuration.StorageOptions#computeCRC32C()}.
	 */
	private final ChecksumFactory checksumFactory;
	/**
	 * The size of single region of {@link #memoryBlock} in Bytes.
	 */
	protected final int regionSize;
	/**
	 * The size of the memory block in Bytes.
	 */
	protected final long sizeInBytes;
	/**
	 * The size of {@link #memoryBlock} as the `int` a direct buffer is allocated with. It is converted when the manager
	 * is created, so that a size no direct buffer can hold fails at startup rather than in the first transaction.
	 */
	private final int memoryBlockSize;
	/**
	 * Represents a concurrent fixed-size array of OffHeapMemoryOutputStream objects, used as regions. Each non-null
	 * output stream has single region block reserved in {@link #memoryBlock}.
	 */
	protected final AtomicReferenceArray<OffHeapMemoryOutputStream> usedRegions;
	/**
	 * The off-heap memory block the regions are sliced from. It is `null` until the first region is acquired, and again
	 * once the block has been freed after the manager closed.
	 */
	final AtomicReference<ByteBuffer> memoryBlock;
	/**
	 * Set once by {@link #close()}. A closed manager hands out no further region, and frees its memory block as soon as
	 * no region is in use.
	 */
	private final AtomicBoolean closed = new AtomicBoolean(false);
	/**
	 * Serializes the allocation of {@link #memoryBlock} with its release, so that a block is never allocated after it
	 * was freed and never freed twice.
	 */
	private final ReentrantLock memoryBlockLock = new ReentrantLock();
	/**
	 * The last index of the usedRegions array that was used to acquire a region. This is used to identify the first
	 * attempted index to acquire a region. We tested a randomization function to select it, but it's miss count varied
	 * a lot and this simple mechanism proved to be very easy to implement and not so worse than the randomization.
	 */
	private int lastIndex = 0;

	/**
	 * Looks up `sun.misc.Unsafe#invokeCleaner` and binds it to the `Unsafe` instance.
	 *
	 * @return the bound method handle, or `null` when this JDK does not offer it
	 */
	@Nullable
	private static MethodHandle lookupInvokeCleaner() {
		try {
			final Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
			final Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			return MethodHandles.lookup()
				.findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class))
				.bindTo(theUnsafe.get(null));
		} catch (ReflectiveOperationException | RuntimeException ex) {
			log.warn(
				"Off-heap transaction memory cannot be freed explicitly on this JDK, closed catalogs release it only " +
					"when the garbage collector collects it: " + ex.getMessage()
			);
			return null;
		}
	}

	/**
	 * Frees the memory of the given direct buffer at once. When this JDK does not allow it, or freeing fails, the
	 * buffer is left to the garbage collector, which releases its memory when it collects the buffer object.
	 *
	 * @param memoryBlock the buffer allocated by {@link ByteBuffer#allocateDirect(int)}, never a slice of it, that no
	 *                    region uses any more
	 */
	private static void free(@Nonnull ByteBuffer memoryBlock) {
		if (INVOKE_CLEANER != null) {
			try {
				INVOKE_CLEANER.invokeExact(memoryBlock);
			} catch (Error ex) {
				throw ex;
			} catch (Throwable ex) {
				log.warn("Failed to free off-heap transaction memory, it is left to the garbage collector.", ex);
			}
		}
	}

	/**
	 * Creates a manager of a memory block split into equal regions. No memory is allocated here - the block is
	 * allocated when the first region is acquired. A manager with no memory or no regions hands out no region at all.
	 *
	 * @param sizeInBytes     the size of the whole memory block in bytes, at most `Integer.MAX_VALUE`
	 * @param regions         the number of regions the block is split into; the remainder of the division stays unused
	 * @param checksumFactory the factory of the checksum each acquired region stream receives
	 * @throws ArithmeticException when `sizeInBytes` is larger than a direct buffer can hold
	 */
	public OffHeapMemoryManager(
		long sizeInBytes,
		int regions,
		@Nonnull ChecksumFactory checksumFactory
	) {
		this.sizeInBytes = sizeInBytes;
		if (regions == 0 || sizeInBytes == 0) {
			this.memoryBlockSize = 0;
			this.regionSize = 0;
			this.usedRegions = new AtomicReferenceArray<>(0);
		} else {
			this.memoryBlockSize = Math.toIntExact(sizeInBytes);
			if (sizeInBytes % regions != 0) {
				log.warn(
					"You're wasting memory - off heap memory block size is not divisible by number of regions without " +
						"remainder (" + sizeInBytes + " / " + regions + ")!");
			}
			this.regionSize = Math.toIntExact(sizeInBytes / regions);
			this.usedRegions = new AtomicReferenceArray<>(regions);
		}
		// the memory is allocated when the first region is acquired
		this.memoryBlock = new AtomicReference<>(null);
		this.checksumFactory = checksumFactory;
	}

	/**
	 * Acquires a free region stream from the memory block, allocating the block on the first acquisition.
	 *
	 * @return an {@link Optional} containing the acquired {@link OutputStream}, or an empty {@link Optional}
	 * if no free region is available or the manager is closed.
	 */
	@Nonnull
	public Optional<OffHeapMemoryOutputStream> acquireRegionOutputStream() {
		final int regionCount = this.usedRegions.length();
		if (regionCount == 0) {
			return Optional.empty();
		}
		final ByteBuffer byteBuffer = getOrAllocateMemoryBlock();
		if (byteBuffer == null) {
			return Optional.empty();
		}
		final OffHeapMemoryOutputStream newOutputStream = new OffHeapMemoryOutputStream(
			this.checksumFactory.createChecksum()
		);
		final int occupiedIndex = findClearIndexAndSet(regionCount, this.lastIndex++, newOutputStream);
		this.lastIndex = occupiedIndex;
		if (occupiedIndex == -1) {
			return Optional.empty();
		} else if (this.closed.get()) {
			// the manager closed after the block was obtained - when no region was in use at that moment, the block
			// may be freed already and must not be touched; give the region back, so that it can be freed
			this.usedRegions.compareAndSet(occupiedIndex, newOutputStream, null);
			freeMemoryBlockIfUnused();
			return Optional.empty();
		} else {
			// the occupied region keeps the block allocated: a closing manager now sees it in use and leaves the block
			// to the release of the region
			final ByteBuffer region = byteBuffer.slice(occupiedIndex * this.regionSize, this.regionSize);
			newOutputStream.init(
				occupiedIndex, region,
				(index, clearedReference) -> {
					this.usedRegions.compareAndSet(index, clearedReference, null);
					if (!this.closed.get() || !freeMemoryBlockIfUnused()) {
						emitUsageWhileAllocated(regionCount);
					}
				}
			);

			// emit the event
			emitAllocationEvent(this.sizeInBytes, (long) (regionCount - getFreeRegions()) * this.regionSize);
			return Optional.of(newOutputStream);
		}
	}

	/**
	 * Closes the manager: no further region is handed out. The memory block is freed at once when no region is in use,
	 * otherwise by the release of the last region still in use. Regions in use stay readable and writable until their
	 * owners release them - a WAL reference may still be copying its region when the catalog closes.
	 */
	@Override
	public void close() {
		if (this.closed.compareAndSet(false, true) && !freeMemoryBlockIfUnused() && this.memoryBlock.get() != null) {
			log.debug(
				"Off-heap memory manager closed with {} regions still in use, its memory is freed when they are " +
					"released.",
				this.usedRegions.length() - getFreeRegions()
			);
		}
	}

	/**
	 * Returns the number of free regions.
	 *
	 * @return the number of free regions
	 */
	public int getFreeRegions() {
		// iterate over the used regions and count the null values
		int freeRegions = 0;
		for (int i = 0; i < this.usedRegions.length(); i++) {
			if (this.usedRegions.get(i) == null) {
				freeRegions++;
			}
		}
		return freeRegions;
	}

	/**
	 * Emits an event indicating a change in off-heap memory allocation.
	 *
	 * @param allocatedMemoryBytes the amount of memory allocated for off-heap storage in bytes
	 * @param usedMemoryBytes the amount of memory currently used for off-heap storage in bytes
	 */
	protected void emitAllocationEvent(long allocatedMemoryBytes, long usedMemoryBytes) {
		new OffHeapMemoryAllocationChangeEvent(allocatedMemoryBytes, usedMemoryBytes).commit();
	}

	/**
	 * Emits the memory in use after a region was released, while the block is still allocated. A close that runs after
	 * the release checked {@link #closed} may free the block and report nothing allocated; emitting under the lock the
	 * free holds, and only while the block exists, keeps this report from landing after that one and showing a freed
	 * block as allocated.
	 *
	 * @param regionCount the total number of regions
	 */
	private void emitUsageWhileAllocated(int regionCount) {
		this.memoryBlockLock.lock();
		try {
			if (this.memoryBlock.get() != null) {
				emitAllocationEvent(this.sizeInBytes, (long) (regionCount - getFreeRegions()) * this.regionSize);
			}
		} finally {
			this.memoryBlockLock.unlock();
		}
	}

	/**
	 * Returns the memory block, allocating it when no region has been acquired yet.
	 *
	 * @return the memory block, or `null` when the manager is closed and the block is not allocated or already freed
	 */
	@Nullable
	private ByteBuffer getOrAllocateMemoryBlock() {
		final ByteBuffer existingBlock = this.memoryBlock.get();
		if (existingBlock != null) {
			return existingBlock;
		}
		this.memoryBlockLock.lock();
		try {
			if (this.closed.get()) {
				return null;
			}
			final ByteBuffer currentBlock = this.memoryBlock.get();
			if (currentBlock != null) {
				return currentBlock;
			}
			final ByteBuffer newBlock = ByteBuffer.allocateDirect(this.memoryBlockSize);
			this.memoryBlock.set(newBlock);
			return newBlock;
		} finally {
			this.memoryBlockLock.unlock();
		}
	}

	/**
	 * Frees the memory block when the manager is closed and no region is in use.
	 *
	 * A region is occupied in {@link #usedRegions} before its acquirer checks {@link #closed}, and `close` sets
	 * {@link #closed} before this method checks the regions. So either the acquirer sees the manager closed and gives
	 * the region back without touching the block, or this method sees the region in use and leaves the block to its
	 * release.
	 *
	 * @return true when the manager is closed and holds no memory block any more
	 */
	private boolean freeMemoryBlockIfUnused() {
		this.memoryBlockLock.lock();
		try {
			if (!this.closed.get() || getFreeRegions() < this.usedRegions.length()) {
				return false;
			}
			final ByteBuffer block = this.memoryBlock.getAndSet(null);
			if (block != null) {
				free(block);
				emitAllocationEvent(0L, 0L);
			}
			return true;
		} finally {
			this.memoryBlockLock.unlock();
		}
	}

	/**
	 * Finds a clear index in the usedRegions array by comparing and setting the value at the specified index with
	 * a newOutputStreamFactory object.
	 *
	 * @param regionCount     the total number of regions
	 * @param randomIndex     the random starting index
	 * @param newOutputStream the new OutputStream object
	 * @return the clear index found, or -1 if no clear index is found
	 */
	private int findClearIndexAndSet(int regionCount, int randomIndex, @Nonnull OffHeapMemoryOutputStream newOutputStream) {
		for (int i = 0; i < regionCount; i++) {
			final int index = Math.abs(randomIndex + i) % regionCount;
			if (this.usedRegions.compareAndSet(index, null, newOutputStream)) {
				return index;
			}
		}
		return -1;
	}
}
