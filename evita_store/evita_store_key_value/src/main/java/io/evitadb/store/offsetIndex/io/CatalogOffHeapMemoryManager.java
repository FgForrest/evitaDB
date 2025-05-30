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

package io.evitadb.store.offsetIndex.io;

import io.evitadb.core.metric.event.transaction.OffHeapMemoryAllocationChangeEvent;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;

/**
 * CatalogOffHeapMemoryManager class is responsible for managing off-heap memory regions and providing
 * free regions to acquire OutputStreams for writing data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Slf4j
public class CatalogOffHeapMemoryManager extends OffHeapMemoryManager {
	/**
	 * The name of the catalog that this memory manager is associated with.
	 */
	@Getter private final String catalogName;

	public CatalogOffHeapMemoryManager(@Nonnull String catalogName, long sizeInBytes, int regions) {
		super(sizeInBytes, regions);
		this.catalogName = catalogName;
	}

	@Override
	protected void emitAllocationEvent(long allocatedMemoryBytes, long usedMemoryBytes) {
		new OffHeapMemoryAllocationChangeEvent(this.catalogName, allocatedMemoryBytes, usedMemoryBytes).commit();
	}
}
