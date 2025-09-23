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
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.reference.translator.ReferencePropertyTranslator;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * This interface extends {@link EntityComparator} and allows to bind comparison with some specific referenced entity
 * id. This context is used in {@link ReferencePropertyTranslator} when multiple references are traversed.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public interface EntityReferenceSensitiveComparator extends EntityComparator {

	/**
	 * Executes the provided {@code lambda} within the context of a specific referenced entity ID.
	 *
	 * @param referenceKey The identifier of the reference to be used as the context for the lambda execution.
	 * @param lambda        The executable task to be performed within the context of the referenced entity ID.
	 */
	void withReferencedEntityId(@Nonnull RepresentativeReferenceKey referenceKey, @Nonnull Runnable lambda);

	/**
	 * Retrieves a reference from the given entity based on the provided representative reference key.
	 * The reference is located by matching the representative attribute values, if they exist.
	 *
	 * @param entity The entity from which the reference will be retrieved. Must not be null.
	 * @param referenceSchema The schema defining the reference attributes. Must not be null.
	 * @param representativeReferenceKey The key containing the reference and representative attribute values. Must not be null.
	 * @return An {@link Optional} containing the matching {@link ReferenceContract}, or an empty Optional if no match is found.
	 */
	@Nonnull
	static Optional<ReferenceContract> getReferenceByRepresentativeReferenceKey(
		@Nonnull EntityContract entity,
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull RepresentativeReferenceKey representativeReferenceKey
	) {
		final Serializable[] expectedRAV = representativeReferenceKey.representativeAttributeValues();
		if (ArrayUtils.isEmpty(expectedRAV)) {
			return entity.getReference(representativeReferenceKey.referenceKey());
		} else {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			final List<ReferenceContract> matchingReferences = entity.getReferences(representativeReferenceKey.referenceKey());
			ReferenceContract foundReference = null;
			for (ReferenceContract matchingReference : matchingReferences) {
				final Serializable[] representativeValues = rad.getRepresentativeValues(matchingReference);
				if (Arrays.equals(representativeValues, expectedRAV)) {
					Assert.isPremiseValid(
						foundReference == null,
						() -> "Duplicate references found for " + Arrays.toString(expectedRAV) + ", which should not happen!"
					);
					foundReference = matchingReference;
				}
			}
			return ofNullable(foundReference);
		}
	}

}
