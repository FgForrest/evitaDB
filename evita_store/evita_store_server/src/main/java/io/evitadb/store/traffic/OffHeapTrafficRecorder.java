/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.traffic;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.util.Pool;
import io.evitadb.api.configuration.ServerOptions;
import io.evitadb.api.query.Query;
import io.evitadb.api.requestResponse.mutation.Mutation;
import io.evitadb.core.traffic.TrafficRecorder;
import io.evitadb.store.query.QuerySerializationKryoConfigurer;
import io.evitadb.store.service.KryoFactory;
import io.evitadb.store.traffic.data.SessionTraffic;
import io.evitadb.store.wal.WalKryoConfigurer;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.Map;
import java.util.PrimitiveIterator.OfInt;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class OffHeapTrafficRecorder implements TrafficRecorder, Closeable {
	private static final Pool<Kryo> OFF_HEAP_TRAFFIC_RECORDER_KRYO_POOL = new Pool<>(true, false) {
		@Override
		protected Kryo create() {
			return KryoFactory.createKryo(
				WalKryoConfigurer.INSTANCE
					.andThen(QuerySerializationKryoConfigurer.INSTANCE)
			);
		}
	};
	/**
	 * Size of a single memory slot used for storing queries and mutations.
	 */
	public static final int BLOCK_SIZE_BYTES = 1_024;
	/**
	 * Private final variable to store a reference to a ByteBuffer object.
	 * The AtomicReference class is used to provide thread-safe access to the memoryBlock.
	 */
	private final AtomicReference<ByteBuffer> memoryBlock = new AtomicReference<>();
	/**
	 * Map contains all tracked sessions and their traffic data indexed by session ID.
	 */
	private final Map<UUID, SessionTraffic> trackedSessionsIndex = new ConcurrentHashMap<>(256);
	/**
	 * Queue of session traffic data ordered by the date of their creation. This queue is used to find oldest finalized
	 * sessions for eviction.
	 */
	private final Queue<SessionTraffic> sessionHistory = new ConcurrentLinkedQueue<>();
	/**
	 * Queue contains indexes of free blocks available for usage.
	 */
	private Queue<Integer> freeBlocks;

	@Override
	public void init(@Nonnull ServerOptions serverOptions) {
		final long trafficMemoryBufferSizeInBytes = serverOptions.trafficMemoryBufferSizeInBytes();
		Assert.isPremiseValid(
			trafficMemoryBufferSizeInBytes > 0,
			"Traffic memory buffer size must be greater than 0."
		);
		// align the buffer size to be divisible by 1KB page size
		final int capacity = (int) (trafficMemoryBufferSizeInBytes - (trafficMemoryBufferSizeInBytes % BLOCK_SIZE_BYTES));
		this.memoryBlock.set(ByteBuffer.allocateDirect(capacity));
		final int blockCount = capacity / BLOCK_SIZE_BYTES;
		// initialize free blocks queue, all blocks are free at the beginning
		this.freeBlocks = new ArrayBlockingQueue<>(
			blockCount, true,
			Stream.generate(() -> 0).limit(blockCount).toList()
		);
	}

	@Override
	public void createSession(@Nonnull UUID sessionId, long catalogVersion, @Nonnull OffsetDateTime created) {
		final SessionTraffic sessionTraffic = new SessionTraffic(sessionId, catalogVersion, created);
		this.trackedSessionsIndex.put(sessionId, sessionTraffic);
		this.sessionHistory.add(sessionTraffic);
	}

	@Override
	public void closeSession(@Nonnull UUID sessionId) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.remove(sessionId);
		if (sessionTraffic != null) {
			sessionTraffic.finish();
		}
	}

	@Override
	public void recordQuery(@Nonnull UUID sessionId, @Nonnull Query query, int totalRecordCount, @Nonnull int[] primaryKeys) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.get(sessionId);
		if (sessionTraffic != null) {
			final int blockPeek = prepareStorageBlock(sessionTraffic);
			/* TODO JNO - tady bude potřeba naučit StorageRecord zapisovat do různých regionů */
			/* TODO JNO - možná strčit ObservableOutput přímo dovnitř SessionTrafficu a peek neřešit (ten je uvnitř) */
			/* TODO JNO - možná udělat chytrý OutputStream, který si bude umět doalokovat paměť, když bude chybět */
			/*final StorageRecord<QueryContainer> queryContainerStorageRecord = new StorageRecord<>(
				OFF_HEAP_TRAFFIC_RECORDER_KRYO_POOL.obtain(),
				new ObservableOutput<>(),
				0L,
				false,
				new QueryContainer(sessionId, query, totalRecordCount, primaryKeys)
			);*/
		}
	}

	@Override
	public void recordMutation(@Nonnull UUID sessionId, @Nonnull Mutation... mutation) {
		final SessionTraffic sessionTraffic = this.trackedSessionsIndex.get(sessionId);
		if (sessionTraffic != null) {
			final int blockPeek = prepareStorageBlock(sessionTraffic);
			// TODO JNO - serialize mutation
		}
	}

	/**
	 * Method prepares storage block for the session traffic. If the block is full, the method tries to free some memory
	 * to free up space. Returns peek index of the free memory block allocated to this session.
	 *
	 * @param sessionTraffic the session traffic record
	 * @return the peek index of the free memory block allocated to this session
	 */
	private int prepareStorageBlock(@Nonnull SessionTraffic sessionTraffic) {
		if (sessionTraffic.isFull(BLOCK_SIZE_BYTES)) {
			Integer freeBlockId = this.freeBlocks.poll();
			if (freeBlockId == null) {
				freeBlockId = freeMemory();
			}
			sessionTraffic.addMemoryBlock(freeBlockId);
		}
		return sessionTraffic.getBlockPeek();
	}

	@Override
	public void close() throws IOException {
		this.memoryBlock.set(null);
		this.trackedSessionsIndex.clear();
		this.sessionHistory.clear();
	}

	/**
	 * Drops finished session from the memory to free up space and returns next free block ID. Method tries to release
	 * 30% of the allocated memory.
	 */
	private int freeMemory() {
		final int minimalBlockCount = (int) (0.3 * memoryBlock.get().capacity() / BLOCK_SIZE_BYTES);
		FreeOperationResult result = freeSessions(0, minimalBlockCount, null, SessionTraffic::isFinished);
		if (result.freedBlocks() < minimalBlockCount) {
			result = freeSessions(result.freedBlocks(), minimalBlockCount, result.firstFreeBlock(), whatever -> true);
		}
		Assert.isPremiseValid(result.firstFreeBlock() != null, "No free block unexpectedly available.");
		return result.firstFreeBlock();
	}

	/**
	 * Method iterates over all tracked sessions and frees memory blocks according to the freeingPredicate. Method stops
	 * when freedBlocks count reaches minimalBlockCount or all sessions are processed.
	 *
	 * @param freedBlocks the number of blocks that were freed
	 * @param minimalBlockCount the minimal number of blocks to free
	 * @param firstFreeBlock the first free block
	 * @param freeingPredicate the predicate the session must satisfy to be freed
	 * @return the result of the free operation
	 */
	@Nonnull
	private FreeOperationResult freeSessions(
		int freedBlocks,
		int minimalBlockCount,
		@Nullable Integer firstFreeBlock,
		@Nonnull Predicate<SessionTraffic> freeingPredicate
	) {
		final Iterator<SessionTraffic> iteratorAgain = this.sessionHistory.iterator();
		// free also non finished sessions
		while (iteratorAgain.hasNext() && freedBlocks < minimalBlockCount) {
			final SessionTraffic sessionTraffic = iteratorAgain.next();
			if (freeingPredicate.test(sessionTraffic)) {
				freedBlocks++;
				iteratorAgain.remove();
				final OfInt memoryBlockIdsToFree = sessionTraffic.getMemoryBlockIds();
				while (memoryBlockIdsToFree.hasNext()) {
					if (firstFreeBlock == null) {
						firstFreeBlock = memoryBlockIdsToFree.next();
					} else {
						this.freeBlocks.offer(memoryBlockIdsToFree.next());
					}
				}
			}
		}
		return new FreeOperationResult(
			freedBlocks,
			firstFreeBlock
		);
	}

	/**
	 * Record of the result of the free operation.
	 * @param freedBlocks the number of blocks that were freed
	 * @param firstFreeBlock the first free block
	 */
	private record FreeOperationResult(
		int freedBlocks,
		@Nullable Integer firstFreeBlock
	) {

	}

}
