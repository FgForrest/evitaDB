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

package io.evitadb.api.requestResponse.data.structure.predicate;

import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.exception.ContextMissingException;
import io.evitadb.api.requestResponse.EvitaRequest;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.data.structure.EntityDecorator;
import io.evitadb.api.requestResponse.data.structure.SerializablePredicate;
import io.evitadb.utils.Assert;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;

/**
 * This predicate allows limiting hierarchy information visible to the client based on query constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchySerializablePredicate implements SerializablePredicate<Integer> {
	public static final HierarchySerializablePredicate DEFAULT_INSTANCE = new HierarchySerializablePredicate(true);
	@Serial private static final long serialVersionUID = 4588087391156067079L;
	/**
	 * Contains true if hierarchy of the entity has been fetched / requested.
	 */
	@Getter private final boolean requiresHierarchy;
	/**
	 * Contains information about underlying predicate that is bound to the {@link EntityDecorator}. This underlying
	 * predicate represents the scope of the fetched (enriched) entity in its true form (i.e. {@link Entity}) and needs
	 * to be carried around even if {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)}
	 * is invoked on the entity.
	 */
	@Nullable @Getter private final HierarchySerializablePredicate underlyingPredicate;

	public HierarchySerializablePredicate() {
		this.requiresHierarchy = false;
		this.underlyingPredicate = null;
	}

	public HierarchySerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.requiresHierarchy = evitaRequest.isRequiresParent();
		this.underlyingPredicate = null;
	}

	public HierarchySerializablePredicate(
		@Nonnull EvitaRequest evitaRequest,
		@Nonnull HierarchySerializablePredicate underlyingPredicate
	) {
		Assert.isPremiseValid(
			underlyingPredicate.getUnderlyingPredicate() == null,
			"Underlying predicates cannot be nested! " +
				"Underlying predicate composition expects to be maximally one: " +
				"limited view -> complete view and never limited view -> limited view -> complete view."
		);
		this.requiresHierarchy = evitaRequest.isRequiresParent();
		this.underlyingPredicate = underlyingPredicate;
	}

	public HierarchySerializablePredicate(
		boolean requiresHierarchy
	) {
		this.requiresHierarchy = requiresHierarchy;
		this.underlyingPredicate = null;
	}

	/**
	 * Returns true if the attributes were fetched along with the entity.
	 */
	public boolean wasFetched() {
		return this.requiresHierarchy;
	}

	/**
	 * Method verifies that attributes was fetched with the entity.
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresHierarchy) {
			throw ContextMissingException.hierarchyContextMissing();
		}
	}

	@Override
	public boolean test(Integer attributeValue) {
		if (this.requiresHierarchy) {
			return true;
		} else {
			return false;
		}
	}

	public HierarchySerializablePredicate createRicherCopyWith(@Nonnull EvitaRequest evitaRequest) {
		if ((this.requiresHierarchy || this.requiresHierarchy == evitaRequest.isRequiresParent())) {
			return this;
		} else {
			return new HierarchySerializablePredicate(
				evitaRequest.isRequiresParent()
			);
		}
	}

}
