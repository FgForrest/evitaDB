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

package io.evitadb.spi.store.catalog.persistence;

import io.evitadb.api.requestResponse.data.structure.predicate.ReferenceDecodeCoverage;
import io.evitadb.spi.store.catalog.persistence.storageParts.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * Carries the {@link ReferenceDecodeCoverage} a read is allowed to materialize down to the Kryo deserializer of
 * {@link ReferencesStoragePart}, the same way {@link EntitySchemaContext} carries the entity schema.
 *
 * A {@link ReferencesStoragePart} holds **every** reference of an entity - including the back-references pointing at
 * it - while a query projection typically asks for one of them. Decoding the rest is pure waste: on a production
 * catalogue a single popular entity was measured decoding 72 218 references to hand one of them to the caller, and
 * each decoded reference costs a `String`, a `ReferenceKey`, an attribute map and a `Reference` instance, plus a slot
 * in the entity's reference index. The deserializer consults this context and skips the binary payload of every
 * reference the coverage does not admit - by name, and within a name by referenced entity primary key - so those
 * objects are never created.
 *
 * The filter is a **read-side narrowing only**. A part decoded under a coverage remembers it (see
 * {@link ReferencesStoragePart#getDecodeCoverage()}) and refuses to be used where completeness is required -
 * the write path in particular must never observe a narrowed part, which is why this context is installed around
 * the read of a single storage part and removed immediately afterwards.
 *
 * Unrestricted reads are expressed by binding a NULL coverage, which is also what a caller that cannot enumerate the
 * references it will need must pass - there is no global switch that turns the narrowing off, so every read states
 * its own requirement.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 * @see EntitySchemaContext
 */
public class ReferenceDecodeCoverageContext {
	/**
	 * What the currently executing read may materialize, or NULL when the read is unrestricted. Bound to the thread
	 * that performs the deserialization - the fetch pipeline is single threaded and the deserializer has no access
	 * to the request, exactly like {@link EntitySchemaContext}.
	 */
	private static final ThreadLocal<ReferenceDecodeCoverage> DECODE_COVERAGE = new ThreadLocal<>();

	/**
	 * Executes the passed lambda with the decode coverage bound to the current thread, restoring whatever was bound
	 * before once it finishes.
	 *
	 * @param coverage what may be materialized, NULL when everything may be
	 * @param lambda   the read to be performed under the coverage
	 * @return whatever the lambda returns
	 */
	public static <T> T executeWithCoverage(
		@Nullable ReferenceDecodeCoverage coverage,
		@Nonnull Supplier<T> lambda
	) {
		final ReferenceDecodeCoverage previousCoverage = DECODE_COVERAGE.get();
		if (coverage == null) {
			DECODE_COVERAGE.remove();
		} else {
			DECODE_COVERAGE.set(coverage);
		}
		try {
			return lambda.get();
		} finally {
			if (previousCoverage == null) {
				DECODE_COVERAGE.remove();
			} else {
				DECODE_COVERAGE.set(previousCoverage);
			}
		}
	}

	/**
	 * Returns what the currently executing read may materialize.
	 *
	 * @return the coverage, or NULL when the read is unrestricted (the default outside
	 * {@link #executeWithCoverage(ReferenceDecodeCoverage, Supplier)})
	 */
	@Nullable
	public static ReferenceDecodeCoverage getDecodeCoverage() {
		return DECODE_COVERAGE.get();
	}

	private ReferenceDecodeCoverageContext() {
	}

}
