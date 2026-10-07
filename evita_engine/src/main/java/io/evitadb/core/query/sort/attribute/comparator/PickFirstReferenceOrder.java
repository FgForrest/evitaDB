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

package io.evitadb.core.query.sort.attribute.comparator;

import io.evitadb.api.query.order.PickFirstByEntityProperty;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.data.structure.RepresentativeReferenceKey;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.RepresentativeAttributeDefinition;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;

import javax.annotation.Nonnull;
import java.util.Comparator;
import java.util.function.IntUnaryOperator;

/**
 * Creates the order in which the prefetch route of a {@link PickFirstByEntityProperty} ordering considers the
 * references of one entity - the same order in which {@link PickFirstReducedIndexResolver} lines up the reduced
 * indexes for the index route, so that both routes pick the same reference:
 *
 * 1. by the rank of the referenced entity in target order,
 * 2. references sharing one referenced entity - possible only when the reference allows duplicates - by their
 *    representative attribute values in {@link RepresentativeReferenceKey#GENERIC_COMPARATOR} order.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReferenceOrder {

	private PickFirstReferenceOrder() {
		// utility class
	}

	/**
	 * Creates the comparator of references of one entity.
	 *
	 * @param referenceSchema the reference being ordered by
	 * @param targetRank      function returning a lower number for a referenced entity that comes first
	 * @return comparator ordering the references in which they are considered
	 */
	@Nonnull
	public static Comparator<ReferenceContract> create(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull IntUnaryOperator targetRank
	) {
		final Comparator<ReferenceContract> byTarget = Comparator.comparingInt(
			reference -> targetRank.applyAsInt(reference.getReferencedPrimaryKey())
		);
		if (referenceSchema.getCardinality().allowsDuplicates()) {
			final RepresentativeAttributeDefinition rad = referenceSchema.getRepresentativeAttributeDefinition();
			return byTarget.thenComparing(
				reference -> new RepresentativeReferenceKey(reference.getReferenceKey(), rad.getRepresentativeValues(reference)),
				RepresentativeReferenceKey.GENERIC_COMPARATOR
			);
		} else {
			return byTarget;
		}
	}

}
