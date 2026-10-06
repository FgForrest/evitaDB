/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

// TODO JNO - remove at the end of #1615 (the whole file)
package io.evitadb.index.virtual;

import io.evitadb.core.catalog.VirtualReferencePartitionMaintenance;

import javax.annotation.Nonnull;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Runs one query twice on the same engine and the same data - once reading every collection through its index objects,
 * once with the read switch of the given collections on - and reports both answers together with how many rows of
 * registered single-owner partitions the second run read virtually. A parity case compares the two answers; the count
 * tells it whether the virtual reading was exercised at all, which an equal answer alone cannot.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class VirtualReadParity {

	private VirtualReadParity() {
	}

	/**
	 * Runs the query read-off and then read-on; the read switch is off again when this returns, whatever the query
	 * did.
	 *
	 * @param maintenance the switch holder of the catalog the query reads
	 * @param entityTypes the collections to read virtually in the second run - each must be maintained
	 * @param query       the query
	 * @param <T>         the type of the answer
	 * @return both answers and the virtual rows the second run read
	 */
	@Nonnull
	static <T> Comparison<T> compare(
		@Nonnull VirtualReferencePartitionMaintenance maintenance,
		@Nonnull Set<String> entityTypes,
		@Nonnull Supplier<T> query
	) {
		final T objectReading = query.get();
		return new Comparison<>(objectReading, readVirtually(maintenance, entityTypes, query));
	}

	/**
	 * Runs the query with the read switch of the given collections on; the switch is off again when this returns.
	 *
	 * @param maintenance the switch holder of the catalog the query reads
	 * @param entityTypes the collections to read virtually - each must be maintained
	 * @param query       the query
	 * @param <T>         the type of the answer
	 * @return the answer and the virtual rows the run read
	 */
	@Nonnull
	static <T> VirtualReading<T> readVirtually(
		@Nonnull VirtualReferencePartitionMaintenance maintenance,
		@Nonnull Set<String> entityTypes,
		@Nonnull Supplier<T> query
	) {
		final long rowsBefore = maintenance.getVirtualRowsRead();
		for (final String entityType : entityTypes) {
			maintenance.setReadVirtually(entityType, true);
		}
		try {
			final T answer = query.get();
			return new VirtualReading<>(answer, maintenance.getVirtualRowsRead() - rowsBefore);
		} finally {
			for (final String entityType : entityTypes) {
				maintenance.setReadVirtually(entityType, false);
			}
		}
	}

	/**
	 * The answer of a query read with the switch on, and how many virtual rows it read.
	 *
	 * @param answer          the answer
	 * @param virtualRowsRead the rows of registered single-owner partitions the query answered virtually
	 * @param <T>             the type of the answer
	 */
	record VirtualReading<T>(@Nonnull T answer, long virtualRowsRead) {
	}

	/**
	 * The answers of one query read both ways.
	 *
	 * @param objectReading  the answer read through the index objects
	 * @param virtualReading the answer read with the switch on
	 * @param <T>            the type of the answer
	 */
	record Comparison<T>(@Nonnull T objectReading, @Nonnull VirtualReading<T> virtualReading) {
	}

}
