/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.query.sort;


import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.reference.translator.ReferencePropertyTranslator;
import io.evitadb.utils.ArrayUtils;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

import static java.util.Optional.ofNullable;

/**
 * This interface extends {@link EntityComparator} and allows to bind comparison with some specific referenced entity
 * id. This context is used in {@link ReferencePropertyTranslator} when multiple references are traversed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EntityReferenceSensitiveComparator extends EntityComparator {

	/**
	 * Executes the provided {@code lambda} within the context of a specific referenced entity ID - the traversal block
	 * of that referenced entity. The key names the referenced entity only; its representative attribute values carry
	 * no meaning, since a block holds every row of the entity to the referenced entity (see
	 * {@link #getFirstValuedReference(EntityContract, ReferenceSchema, ReferenceKey, Predicate)}).
	 *
	 * @param referenceKey The identifier of the reference to be used as the context for the lambda execution.
	 * @param lambda        The executable task to be performed within the context of the referenced entity ID.
	 */
	void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda);

	/**
	 * Picks the row by which an entity is represented in the traversal block of one referenced entity: among the rows
	 * of the entity to the referenced entity, the first one that carries the sorted value, rows taken in the order of
	 * their representative attribute values ({@link RepresentativeReferenceKey#GENERIC_COMPARATOR}, `null` values
	 * first). A row without the value passes the entity on to the next row, and an entity with no valued row is passed
	 * on to the next block - this is the very row whose reduced index claims the entity on the index route, where the
	 * providers of a block claim their owners in the same order.
	 *
	 * Without duplicates the entity has at most one row to the referenced entity, and no representative value is
	 * read. With duplicates the representative values are read only when a second valued row has to be compared with
	 * the first one found.
	 *
	 * @param entity           the entity whose row is picked
	 * @param referenceSchema  the schema of the ordered reference
	 * @param referencedEntity the referenced entity of the block, compared without representative attribute values
	 * @param carriesValue     tells whether a row carries the sorted value
	 * @return the picked row, empty when no row of the entity to the referenced entity carries the value
	 */
	@Nonnull
	static Optional<ReferenceContract> getFirstValuedReference(
		@Nonnull EntityContract entity,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull ReferenceKey referencedEntity,
		@Nonnull Predicate<ReferenceContract> carriesValue
	) {
		if (!referenceSchema.getCardinality().allowsDuplicates()) {
			return entity.getReference(referencedEntity).filter(carriesValue);
		}
		final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
		final List<ReferenceContract> rows = entity.getReferences(referencedEntity);
		ReferenceContract picked = null;
		Serializable[] pickedRepresentativeValues = null;
		for (ReferenceContract row : rows) {
			if (carriesValue.test(row)) {
				if (picked == null) {
					picked = row;
				} else {
					if (pickedRepresentativeValues == null) {
						pickedRepresentativeValues = rad.getRepresentativeValues(picked);
					}
					final Serializable[] rowRepresentativeValues = rad.getRepresentativeValues(row);
					if (ArrayUtils.compare(rowRepresentativeValues, pickedRepresentativeValues) < 0) {
						picked = row;
						pickedRepresentativeValues = rowRepresentativeValues;
					}
				}
			}
		}
		return ofNullable(picked);
	}

}
