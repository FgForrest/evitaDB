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

package io.evitadb.index.attribute;

import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.index.EntityTypeClassifierResolver;

import javax.annotation.Nonnull;
import java.util.Arrays;

/**
 * Reads the owning records of a unique index off its value tree, for tests.
 *
 * The unique indexes keep no record-id set of their own - which records carry a value is answered by the attribute's
 * filter indexes - so a test that wants to see "every record this index holds a value for" derives it from the tree,
 * the very data the index answers every lookup from.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class UniqueIndexTestSupport {

	private UniqueIndexTestSupport() {
		throw new UnsupportedOperationException("This class cannot be instantiated!");
	}

	/**
	 * Returns the distinct records owning a value in the index, in ascending order. A folded view owns no values of
	 * its own - its data lives in the shared filter tree - so it yields an empty array.
	 *
	 * @param index the unique index to inspect
	 * @return distinct owning record ids, ascending
	 */
	@Nonnull
	public static int[] ownerRecordIds(@Nonnull UniqueIndex index) {
		return Arrays.stream(index.inlineSnapshot().recordIds()).distinct().sorted().toArray();
	}

	/**
	 * Returns the distinct primary keys of the entities of `entityType` owning a value in the global unique index, in
	 * ascending order.
	 *
	 * @param index      the global unique index to inspect
	 * @param entityType the entity type whose owners to return
	 * @param resolver   translates the entity type primary keys stored in the tree back to names
	 * @return distinct owning primary keys of that entity type, ascending
	 */
	@Nonnull
	public static int[] ownerRecordIds(
		@Nonnull GlobalUniqueIndex index,
		@Nonnull String entityType,
		@Nonnull EntityTypeClassifierResolver resolver
	) {
		return Arrays.stream(index.getEntityReferences(resolver))
			.filter(it -> entityType.equals(it.getType()))
			.mapToInt(EntityReference::primaryKey)
			.distinct()
			.sorted()
			.toArray();
	}

}
