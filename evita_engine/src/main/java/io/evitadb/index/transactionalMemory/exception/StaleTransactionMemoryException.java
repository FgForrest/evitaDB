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

package io.evitadb.index.transactionalMemory.exception;

import io.evitadb.index.transactionalMemory.TransactionalLayerCreator;
import lombok.Getter;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.util.Iterator;
import java.util.List;

/**
 * Exception is thrown when there are uncommitted data that remains after transactional memory commit.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2019
 */
public class StaleTransactionMemoryException extends IllegalStateException {
	@Serial private static final long serialVersionUID = 46100519964114379L;
	@SuppressWarnings("TransientFieldNotInitialized")
	@Getter private final transient List<TransactionalLayerCreator<?>> uncommittedItems;

	public StaleTransactionMemoryException(@Nonnull List<TransactionalLayerCreator<?>> uncommittedItems) {
		super(constructErrorMessage(uncommittedItems));
		this.uncommittedItems = uncommittedItems;
	}

	private static String constructErrorMessage(List<TransactionalLayerCreator<?>> uncommittedItems) {
		final StringBuilder sb = new StringBuilder("Failed to propagate all memory changes in transaction:\n");
		final Iterator<TransactionalLayerCreator<?>> it = uncommittedItems.iterator();
		while (it.hasNext()) {
			final TransactionalLayerCreator<?> item = it.next();
			sb.append("@").append(item.getId()).append(" (").append(item.getClass().getSimpleName()).append("): ").append(item);
			if (it.hasNext()) {
				sb.append(",\n");
			}
		}
		return sb.toString();
	}
}
