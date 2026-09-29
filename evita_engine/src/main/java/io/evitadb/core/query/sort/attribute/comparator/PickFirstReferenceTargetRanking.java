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

import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.core.query.sort.EntityComparator;
import io.evitadb.core.query.sort.reference.sorter.PickFirstReducedIndexResolver;
import io.evitadb.index.bitmap.Bitmap;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Comparator;
import java.util.function.IntUnaryOperator;

/**
 * Bundles the {@link PickFirstReducedIndexResolver} together with the target rank it resolves for one selection and
 * the {@link Comparator} over references derived from that rank - the trio every prefetch-route `pickFirst`
 * comparator needs in order to agree with the index route the same resolver builds. Shared by composition rather
 * than inheritance: {@link PickFirstReferenceAttributeComparator}, {@link PickFirstReferenceCompoundAttributeComparator}
 * and the primary-key equivalent in {@code EntityPrimaryKeyNaturalTranslator} share no common ancestor below
 * {@link EntityComparator}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public final class PickFirstReferenceTargetRanking {

	/**
	 * Resolver providing the target order the index route walks.
	 */
	@Nonnull private final PickFirstReducedIndexResolver indexResolver;
	/**
	 * Picks the first reference in target order among the references the owning comparator considers.
	 */
	@Nonnull private final Comparator<ReferenceContract> referenceOrder;
	/**
	 * Rank of the targets of the entities being sorted, set by {@link #prepareForSelection(Bitmap)}.
	 */
	@Nullable private IntUnaryOperator targetRank;

	/**
	 * Creates the ranking for the given reference and builds the {@link #referenceOrder()} comparator over it,
	 * deferring to {@link #targetRank} for the actual ranks until {@link #prepareForSelection(Bitmap)} resolves
	 * them.
	 *
	 * @param referenceSchema the schema of the reference being ordered
	 * @param indexResolver   resolver providing the target order the index route walks
	 */
	public PickFirstReferenceTargetRanking(
		@Nonnull ReferenceSchema referenceSchema,
		@Nonnull PickFirstReducedIndexResolver indexResolver
	) {
		this.indexResolver = indexResolver;
		this.referenceOrder = PickFirstReferenceOrder.create(
			referenceSchema,
			referencedPrimaryKey -> {
				Assert.isPremiseValid(
					this.targetRank != null,
					"The comparator must be prepared for the selection before it compares entities!"
				);
				return this.targetRank.applyAsInt(referencedPrimaryKey);
			}
		);
	}

	/**
	 * Resolves the target rank for the given selection of owner entities, so that {@link #referenceOrder()} can
	 * order the references of any of them. Mirrors {@link EntityComparator#prepareForSelection(Bitmap)} on the
	 * owning comparator, which delegates to this method.
	 *
	 * @param entityPrimaryKeys primary keys of the entities being sorted
	 */
	public void prepareForSelection(@Nonnull Bitmap entityPrimaryKeys) {
		this.targetRank = this.indexResolver.getTargetRank(entityPrimaryKeys);
	}

	/**
	 * Returns true when the entity lives in a scope the ordering processes. An entity from another scope (possible
	 * when `inScope` narrows the ordering below the scopes of the filter) is never claimed by the index route and
	 * must fall through to the next sorter on the prefetch route as well.
	 *
	 * @param entity the entity being sorted
	 * @return true if the entity may be sorted by the owning comparator
	 */
	public boolean admits(@Nonnull EntityContract entity) {
		return this.indexResolver.isProcessedScope(entity.getScope());
	}

	/**
	 * Returns the comparator that picks the first reference of an entity in target order.
	 *
	 * @return the reference order
	 */
	@Nonnull
	public Comparator<ReferenceContract> referenceOrder() {
		return this.referenceOrder;
	}

}
