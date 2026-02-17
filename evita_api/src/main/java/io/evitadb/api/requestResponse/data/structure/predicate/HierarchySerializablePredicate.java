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
 * Serializable predicate that controls access to entity hierarchy information based on query requirements.
 *
 * This predicate determines whether hierarchy data (parent entity reference) should be visible to clients.
 * Hierarchy information is fetched when the query includes parent requirements (e.g., via `hierarchyContent()`
 * or similar constraints). The predicate is used by {@link EntityDecorator} to enforce lazy loading of hierarchy
 * data and throw {@link ContextMissingException} when clients attempt to access hierarchy data that wasn't
 * fetched with the query.
 *
 * **Thread-safety**: This class is immutable and thread-safe.
 *
 * **Underlying predicate pattern**: Supports an optional underlying predicate that represents the original entity's
 * complete hierarchy scope. This pattern is used when creating limited views from fully-fetched entities.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class HierarchySerializablePredicate implements SerializablePredicate<Integer> {
	public static final HierarchySerializablePredicate DEFAULT_INSTANCE = new HierarchySerializablePredicate(true);
	@Serial private static final long serialVersionUID = 4588087391156067079L;
	/**
	 * Indicates whether hierarchy information (parent reference) was fetched with the entity.
	 * When false, accessing parent data will throw {@link ContextMissingException}.
	 */
	@Getter private final boolean requiresHierarchy;
	/**
	 * Optional underlying predicate representing the complete entity's hierarchy scope. Used when creating
	 * limited views from fully-fetched entities via
	 * {@link io.evitadb.api.EntityCollectionContract#limitEntity(SealedEntity, EvitaRequest, EvitaSessionContract)}.
	 * Must not be nested (only one level allowed).
	 */
	@Nullable @Getter private final HierarchySerializablePredicate underlyingPredicate;

	/**
	 * Creates a default predicate with hierarchy access disabled.
	 */
	public HierarchySerializablePredicate() {
		this.requiresHierarchy = false;
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a hierarchy predicate from an Evita request.
	 *
	 * Extracts hierarchy requirements from the request (typically from `hierarchyContent()` constraints).
	 *
	 * @param evitaRequest the request containing hierarchy requirements
	 */
	public HierarchySerializablePredicate(@Nonnull EvitaRequest evitaRequest) {
		this.requiresHierarchy = evitaRequest.isRequiresParent();
		this.underlyingPredicate = null;
	}

	/**
	 * Creates a hierarchy predicate with an underlying predicate for entity limitation scenarios.
	 *
	 * This constructor is used when applying additional restrictions to an already-fetched entity
	 * (e.g., when calling `limitEntity`). The underlying predicate preserves the original fetch scope.
	 *
	 * @param evitaRequest the request containing new hierarchy requirements
	 * @param underlyingPredicate the predicate representing the original entity's complete hierarchy scope
	 * @throws io.evitadb.exception.GenericEvitaInternalError if underlyingPredicate is already nested
	 */
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

	/**
	 * Creates a hierarchy predicate with explicit hierarchy requirement.
	 *
	 * Used internally for creating enriched copies via {@link #createRicherCopyWith(EvitaRequest)}.
	 *
	 * @param requiresHierarchy whether hierarchy information is required
	 */
	public HierarchySerializablePredicate(
		boolean requiresHierarchy
	) {
		this.requiresHierarchy = requiresHierarchy;
		this.underlyingPredicate = null;
	}

	/**
	 * Checks whether hierarchy information was fetched with the entity.
	 *
	 * @return true if hierarchy data (parent reference) is available
	 */
	public boolean wasFetched() {
		return this.requiresHierarchy;
	}

	/**
	 * Verifies that hierarchy information was fetched with the entity, throwing an exception if not.
	 *
	 * This method should be called before accessing parent entity data to ensure the data is available.
	 *
	 * @throws ContextMissingException if hierarchy data was not fetched with the entity
	 */
	public void checkFetched() throws ContextMissingException {
		if (!this.requiresHierarchy) {
			throw ContextMissingException.hierarchyContextMissing();
		}
	}

	/**
	 * Tests whether hierarchy data should be visible based on query requirements.
	 *
	 * The parentId parameter is not used in the test logic — hierarchy is either fully available or not.
	 * The parameter exists to satisfy the {@link SerializablePredicate} contract.
	 *
	 * @param parentId the parent entity ID (unused, kept for interface compatibility)
	 * @return true if hierarchy data should be visible
	 */
	@Override
	public boolean test(Integer parentId) {
		return this.requiresHierarchy;
	}

	/**
	 * Creates an enriched copy that combines this predicate's hierarchy scope with additional requirements.
	 *
	 * This method is used when progressively enriching an entity with more data. If the new request doesn't
	 * change the hierarchy requirement, returns this instance for efficiency. Hierarchy is a boolean flag,
	 * so enrichment follows OR logic: once required, it remains required.
	 *
	 * @param evitaRequest the request containing additional hierarchy requirements to merge
	 * @return an enriched predicate, or this instance if no changes are needed
	 */
	@Nonnull
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
