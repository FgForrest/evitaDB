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

package io.evitadb.api.requestResponse.mutation.conflict;


import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Function;

/**
 * A scoped context holder for tracking hierarchical entity information during conflict generation.
 * This class provides temporary context scoping for catalog, entity, and reference information,
 * ensuring that nested operations can access the appropriate contextual data while maintaining
 * proper cleanup semantics.
 *
 * The context is designed to be used in a try-finally pattern through its {@code with*} methods,
 * which automatically set and clear context values around the execution of provided runnables.
 *
 * Beyond scoping, the context is the single authority that decides — for the mutation currently being
 * processed — which conflict keys must be produced. Mutations never read the raw
 * {@link ConflictResolution} directly; they ask the {@code shouldEmit*} predicates and
 * {@link #coarsePolicy()} instead. This indirection lets the resolution become schema-aware (per-item
 * {@link ConflictResolutionOverride} inheritance) later without touching a single mutation
 * implementation — the predicates already carry the identifying element name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictGenerationContext {
	/**
	 * The effective conflict resolution that governs key generation for the mutations processed through
	 * this context. It carries the coarse {@link ConflictPolicy} scope together with the active set of
	 * {@link GranularConflictPolicy} refinements.
	 */
	@Nonnull private final ConflictResolution resolution;
	/**
	 * The name of the catalog currently being processed. May be null if not within a catalog context.
	 */
	@Nullable private String catalogName;

	/**
	 * The type of the entity currently being processed. May be null if not within an entity context.
	 */
	@Nullable private String entityType;

	/**
	 * The primary key of the entity currently being processed. May be null if the entity doesn't have
	 * a primary key yet or if not within an entity context.
	 */
	@Nullable private Integer entityPrimaryKey;

	/**
	 * Creates a context governed by the given effective conflict resolution.
	 *
	 * @param resolution the effective conflict resolution that decides which conflict keys are produced
	 */
	public ConflictGenerationContext(@Nonnull ConflictResolution resolution) {
		this.resolution = resolution;
	}

	/**
	 * Returns the coarse conflict scope in effect for the currently processed mutation. Mutations that
	 * fall back to an entity/collection/catalog-wide key consult this instead of the raw resolution.
	 *
	 * @return the effective coarse {@link ConflictPolicy}
	 */
	@Nonnull
	public ConflictPolicy coarsePolicy() {
		return this.resolution.policy();
	}

	/**
	 * Decides whether a mutation touching the named entity attribute must emit its own attribute-scoped
	 * conflict key. True only when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#ENTITY_ATTRIBUTE} refinement is active.
	 *
	 * @param attributeName the name of the entity attribute being mutated
	 * @return true if an entity-attribute conflict key should be produced
	 */
	public boolean shouldEmitEntityAttributeKey(@Nonnull String attributeName) {
		return isGranularityActive(GranularConflictPolicy.ENTITY_ATTRIBUTE);
	}

	/**
	 * Decides whether a mutation touching the named reference must emit its own reference-scoped conflict
	 * key. True only when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#REFERENCE} refinement is active.
	 *
	 * @param referenceName the name of the reference being mutated
	 * @return true if a reference conflict key should be produced
	 */
	public boolean shouldEmitReferenceKey(@Nonnull String referenceName) {
		return isGranularityActive(GranularConflictPolicy.REFERENCE);
	}

	/**
	 * Decides whether a mutation touching the named attribute of the named reference must emit its own
	 * reference-attribute-scoped conflict key. True only when the effective coarse policy is
	 * {@link ConflictPolicy#ENTITY} and the {@link GranularConflictPolicy#REFERENCE_ATTRIBUTE} refinement
	 * is active.
	 *
	 * @param referenceName the name of the reference whose attribute is being mutated
	 * @param attributeName the name of the reference attribute being mutated
	 * @return true if a reference-attribute conflict key should be produced
	 */
	public boolean shouldEmitReferenceAttributeKey(@Nonnull String referenceName, @Nonnull String attributeName) {
		return isGranularityActive(GranularConflictPolicy.REFERENCE_ATTRIBUTE);
	}

	/**
	 * Decides whether a mutation touching the named associated data must emit its own associated-data-scoped
	 * conflict key. True only when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#ASSOCIATED_DATA} refinement is active.
	 *
	 * @param associatedDataName the name of the associated data being mutated
	 * @return true if an associated-data conflict key should be produced
	 */
	public boolean shouldEmitAssociatedDataKey(@Nonnull String associatedDataName) {
		return isGranularityActive(GranularConflictPolicy.ASSOCIATED_DATA);
	}

	/**
	 * Decides whether a mutation touching a price must emit its own price-scoped conflict key. True only
	 * when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#PRICE} refinement is active.
	 *
	 * @return true if a price conflict key should be produced
	 */
	public boolean shouldEmitPriceKey() {
		return isGranularityActive(GranularConflictPolicy.PRICE);
	}

	/**
	 * Decides whether a mutation touching the hierarchy placement must emit its own hierarchy-scoped
	 * conflict key. True only when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#HIERARCHY} refinement is active.
	 *
	 * @return true if a hierarchy conflict key should be produced
	 */
	public boolean shouldEmitHierarchyKey() {
		return isGranularityActive(GranularConflictPolicy.HIERARCHY);
	}

	/**
	 * Returns true when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the given
	 * sub-entity refinement is part of the active granularity set.
	 *
	 * @param granularPolicy the refinement to test
	 * @return true if the refinement is active under an entity-scoped policy
	 */
	private boolean isGranularityActive(@Nonnull GranularConflictPolicy granularPolicy) {
		return this.resolution.policy() == ConflictPolicy.ENTITY &&
			this.resolution.granularity().contains(granularPolicy);
	}

	/**
	 * Retrieves the catalog name associated with the current context.
	 * If the catalog name is not set, an internal error is thrown.
	 *
	 * @return the name of the catalog
	 * @throws GenericEvitaInternalError if the catalog name is not set in the context
	 */
	@Nonnull
	public String getCatalogName() {
		Assert.isPremiseValid(
			this.catalogName != null,
			"Catalog name is not set in the current context!"
		);
		return this.catalogName;
	}

	/**
	 * Determines if the entity type is set in the current context.
	 *
	 * @return true if an entity type is present, false otherwise
	 */
	public boolean isEntityTypePresent() {
		return this.entityType != null;
	}

	/**
	 * Retrieves the entity type associated with the current context.
	 * If the entity type is not set, an internal error is thrown.
	 *
	 * @return the type of the entity
	 * @throws GenericEvitaInternalError if the entity type is not set in the context
	 */
	@Nonnull
	public String getEntityType() {
		Assert.isPremiseValid(
			this.entityType != null,
			"Entity type is not set in the current context!"
		);
		return this.entityType;
	}

	/**
	 * Retrieves the primary key of the entity associated with the current context.
	 *
	 * @return the primary key of the entity
	 */
	@Nullable
	public Integer getEntityPrimaryKey() {
		return this.entityPrimaryKey;
	}

	/**
	 * Executes the given lambda within a catalog name context. The catalog name is set before
	 * execution and automatically cleared afterwards, even if an exception occurs.
	 *
	 * @param catalogName the name of the catalog to set in the context
	 * @param lambda the code to execute within the catalog context
	 */
	@Nonnull
	public <T> T withCatalogName(@Nonnull String catalogName, @Nonnull Function<ConflictGenerationContext, T> lambda) {
		try {
			this.catalogName = catalogName;
			return lambda.apply(this);
		} finally {
			this.catalogName = null;
		}
	}

	/**
	 * Executes the given lambda within an entity context. The entity type and primary key are set
	 * before execution and automatically cleared afterwards, even if an exception occurs.
	 *
	 * @param entityType the type of the entity to set in the context
	 * @param entityPrimaryKey the primary key of the entity, or null if the entity doesn't have one yet
	 * @param lambda the code to execute within the entity context
	 */
	@Nonnull
	public <T> T withEntityType(@Nonnull String entityType, @Nullable Integer entityPrimaryKey, @Nonnull Function<ConflictGenerationContext, T> lambda) {
		try {
			this.entityType = entityType;
			this.entityPrimaryKey = entityPrimaryKey;
			return lambda.apply(this);
		} finally {
			this.entityType = null;
			this.entityPrimaryKey = null;
		}
	}

}
