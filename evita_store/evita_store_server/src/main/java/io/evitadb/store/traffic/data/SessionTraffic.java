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

package io.evitadb.store.traffic.data;

import io.evitadb.dataType.array.CompositeIntArray;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.time.OffsetDateTime;
import java.util.PrimitiveIterator.OfInt;
import java.util.UUID;

/**
 * Session traffic class is used to store information about the session and the memory blocks where the queries and
 * mutations involved in this session are stored. This object is stored in Java heap memory because it's updated
 * with newly allocated memory blocks.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public class SessionTraffic {
	/**
	 * Id of the session.
	 */
	@Getter private final UUID sessionId;
	/**
	 * Catalog version this session targets.
	 */
	@Getter private final long catalogVersion;
	/**
	 * Date and time when the sesion was created.
	 */
	@Getter private final OffsetDateTime created;
	/**
	 * Flag indicating whether the session is finished.
	 */
	@Getter private boolean finished;
	/**
	 * Indexes of memory blocks where the queries and mutations involved in this session are stored.
	 */
	private final CompositeIntArray blockIds;
	/**
	 * Contains index of the last byte occupied by valid data in the current block.
	 */
	@Getter private int blockPeek;

	public SessionTraffic(
		@Nonnull UUID sessionId,
		long catalogVersion,
		@Nonnull OffsetDateTime created
	) {
		this.sessionId = sessionId;
		this.catalogVersion = catalogVersion;
		this.created = created;
		this.blockIds = new CompositeIntArray();
	}

	/**
	 * Method registers new memory block used for storing queries and mutations.
	 * @param blockId Id of the memory block.
	 */
	public void addMemoryBlock(int blockId) {
		this.blockIds.add(blockId);
		this.blockPeek = -1;
	}

	/**
	 * Returns iterator over all registered memory block ids containing queries and mutations of this session in correct
	 * order.
	 * @return Iterator over memory block ids.
	 */
	@Nonnull
	public OfInt getMemoryBlockIds() {
		return this.blockIds.iterator();
	}

	/**
	 * Returns ID of the last memory block registered.
	 * @return ID of the last memory block.
	 */
	public int getLastMemoryBlockId() {
		Assert.isPremiseValid(!this.blockIds.isEmpty(), "No memory block registered for this session.");
		return this.blockIds.getLast();
	}

	/**
	 * Updates the peek value of the current memory block.
	 * @param peek peek value
	 * @param capacity capacity of the memory block
	 */
	public void updatePeek(int peek, int capacity) {
		Assert.isPremiseValid(!isFull(peek, capacity), "Memory block is full.");
		this.blockPeek = peek;
	}

	/**
	 * Returns whether the current memory block is full.
	 * @param capacity capacity of the memory block
	 * @return true if the memory block is full, false otherwise
	 */
	public boolean isFull(int capacity) {
		return this.blockIds.isEmpty() || isFull(this.blockPeek, capacity);
	}

	/**
	 * Returns TRUE if the peek exceeds the capacity.
	 * @param peek peek value
	 * @param capacity capacity value
	 * @return TRUE if the peek exceeds the capacity
	 */
	private static boolean isFull(int peek, int capacity) {
		return peek >= capacity;
	}

	/**
	 * Marks the session as finished.
	 */
	public void finish() {
		this.finished = true;
	}
}
