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


import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.ConflictResolutionOverrideAwareSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Optional;
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
 * {@link #coarsePolicy()} instead. This indirection lets the resolution be computed per entity type from
 * the schema (per-item {@link ConflictResolutionOverride} inheritance) without touching a single mutation
 * implementation — the predicates already carry the identifying element name.
 *
 * The context operates in one of two modes:
 *
 * - **global-backed** (single {@link ConflictResolution} constructor): every entity type resolves to the
 *   same fixed resolution and per-item overrides are ignored. Used where no schema is available — the
 *   historical recompute fallback and unit tests.
 * - **schema-aware** (catalog schema + entity schema accessor constructor): the effective entity-level
 *   resolution is resolved per entity type via {@link EffectiveConflictResolutionResolver} when an entity
 *   is entered through {@link #withEntityType(String, Integer, Function)}, and per-item
 *   {@link ConflictResolutionOverride}s declared on the element schemas refine it. Used on the WAL write
 *   path where the living schema is threaded in.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ConflictGenerationContext {
	/**
	 * The base conflict resolution: the fixed resolution in global-backed mode, or the engine-wide default
	 * forming the base of the schema precedence walk in schema-aware mode. Always present.
	 */
	@Nonnull private final ConflictResolution baseResolution;
	/**
	 * The schema inputs enabling per-entity, schema-aware resolution; null in global-backed mode. When
	 * present, the {@code shouldEmit*} predicates consult the effective resolution resolved per entity type
	 * and the per-item {@link ConflictResolutionOverride}s declared on the element schemas. Bundling the two
	 * correlated inputs into a single nullable field lets one null-check prove both non-null together.
	 */
	@Nullable private final SchemaResolutionScope schemaScope;
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
	 * The entity schema resolved for the entity type currently in scope (schema-aware mode only); null
	 * outside an entity context or when the type has no schema yet. Cached for the duration of a single
	 * {@link #withEntityType(String, Integer, Function)} scope so per-item overrides can be looked up
	 * without re-resolving.
	 */
	@Nullable private EntitySchemaContract currentEntitySchema;

	/**
	 * The effective entity-level resolution computed for the entity type currently in scope (schema-aware
	 * mode only); null outside an entity context. Cached for the duration of a single
	 * {@link #withEntityType(String, Integer, Function)} scope.
	 */
	@Nullable private ConflictResolution currentResolution;

	/**
	 * Creates a global-backed context: every entity type resolves to the given fixed resolution and
	 * per-item {@link ConflictResolutionOverride}s are ignored (no schema is consulted).
	 *
	 * @param resolution the fixed conflict resolution that decides which conflict keys are produced
	 */
	public ConflictGenerationContext(@Nonnull ConflictResolution resolution) {
		this.baseResolution = resolution;
		this.schemaScope = null;
	}

	/**
	 * Creates a schema-aware context: the effective entity-level resolution is resolved per entity type
	 * from the schema precedence chain (entity schema → catalog schema → engine default) and per-item
	 * {@link ConflictResolutionOverride}s declared on the element schemas refine key emission.
	 *
	 * @param engineDefault         the engine-wide default resolution forming the base of the walk
	 * @param catalogSchema         the catalog schema whose resolution overrides the engine default
	 * @param entitySchemaAccessor  accessor returning the entity schema for a type, or null when absent
	 */
	public ConflictGenerationContext(
		@Nonnull ConflictResolution engineDefault,
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
		this.baseResolution = engineDefault;
		this.schemaScope = new SchemaResolutionScope(catalogSchema, entitySchemaAccessor);
	}

	/**
	 * Returns the coarse conflict scope in effect for the currently processed mutation. Mutations that
	 * fall back to an entity/collection/catalog-wide key consult this instead of the raw resolution.
	 *
	 * @return the effective coarse {@link ConflictPolicy}
	 */
	@Nonnull
	public ConflictPolicy coarsePolicy() {
		return resolvedResolution().policy();
	}

	/**
	 * Decides whether a mutation touching the named entity attribute must emit its own attribute-scoped
	 * conflict key. True when the effective coarse policy is {@link ConflictPolicy#ENTITY} and either the
	 * attribute carries a {@link ConflictResolutionOverride#GRANULAR} override or the inherited
	 * {@link GranularConflictPolicy#ENTITY_ATTRIBUTE} refinement is active.
	 *
	 * @param attributeName the name of the entity attribute being mutated
	 * @return true if an entity-attribute conflict key should be produced
	 */
	public boolean shouldEmitEntityAttributeKey(@Nonnull String attributeName) {
		return shouldEmitGranular(GranularConflictPolicy.ENTITY_ATTRIBUTE, attributeOverride(attributeName));
	}

	/**
	 * Decides whether a mutation touching the named reference must emit its own reference-scoped conflict
	 * key. True when the effective coarse policy is {@link ConflictPolicy#ENTITY} and either the reference
	 * carries a {@link ConflictResolutionOverride#GRANULAR} override or the inherited
	 * {@link GranularConflictPolicy#REFERENCE} refinement is active.
	 *
	 * @param referenceName the name of the reference being mutated
	 * @return true if a reference conflict key should be produced
	 */
	public boolean shouldEmitReferenceKey(@Nonnull String referenceName) {
		return shouldEmitGranular(GranularConflictPolicy.REFERENCE, referenceOverride(referenceName));
	}

	/**
	 * Decides whether a mutation touching the named attribute of the named reference must emit its own
	 * reference-attribute-scoped conflict key. True when the effective coarse policy is
	 * {@link ConflictPolicy#ENTITY} and either the reference attribute carries a
	 * {@link ConflictResolutionOverride#GRANULAR} override or the inherited
	 * {@link GranularConflictPolicy#REFERENCE_ATTRIBUTE} refinement is active.
	 *
	 * @param referenceName the name of the reference whose attribute is being mutated
	 * @param attributeName the name of the reference attribute being mutated
	 * @return true if a reference-attribute conflict key should be produced
	 */
	public boolean shouldEmitReferenceAttributeKey(@Nonnull String referenceName, @Nonnull String attributeName) {
		return shouldEmitGranular(
			GranularConflictPolicy.REFERENCE_ATTRIBUTE,
			referenceAttributeOverride(referenceName, attributeName)
		);
	}

	/**
	 * Decides whether a mutation touching the named associated data must emit its own associated-data-scoped
	 * conflict key. True when the effective coarse policy is {@link ConflictPolicy#ENTITY} and either the
	 * associated data carries a {@link ConflictResolutionOverride#GRANULAR} override or the inherited
	 * {@link GranularConflictPolicy#ASSOCIATED_DATA} refinement is active.
	 *
	 * @param associatedDataName the name of the associated data being mutated
	 * @return true if an associated-data conflict key should be produced
	 */
	public boolean shouldEmitAssociatedDataKey(@Nonnull String associatedDataName) {
		return shouldEmitGranular(GranularConflictPolicy.ASSOCIATED_DATA, associatedDataOverride(associatedDataName));
	}

	/**
	 * Decides whether a mutation touching a price must emit its own price-scoped conflict key. True only
	 * when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#PRICE} refinement is active. Prices carry no per-item schema object, so
	 * they are only declarable through the entity-schema granularity set.
	 *
	 * @return true if a price conflict key should be produced
	 */
	public boolean shouldEmitPriceKey() {
		return shouldEmitGranular(GranularConflictPolicy.PRICE, null);
	}

	/**
	 * Decides whether a mutation touching the hierarchy placement must emit its own hierarchy-scoped
	 * conflict key. True only when the effective coarse policy is {@link ConflictPolicy#ENTITY} and the
	 * {@link GranularConflictPolicy#HIERARCHY} refinement is active. Hierarchy placement carries no per-item
	 * schema object, so it is only declarable through the entity-schema granularity set.
	 *
	 * @return true if a hierarchy conflict key should be produced
	 */
	public boolean shouldEmitHierarchyKey() {
		return shouldEmitGranular(GranularConflictPolicy.HIERARCHY, null);
	}

	/**
	 * Central granular emission decision shared by every {@code shouldEmit*} predicate. Granular
	 * refinements are only meaningful under an {@link ConflictPolicy#ENTITY} coarse policy — a coarser
	 * scope ({@link ConflictPolicy#CATALOG}/{@link ConflictPolicy#COLLECTION}) or {@link ConflictPolicy#NONE}
	 * dominates and suppresses per-element keys. Within an entity-scoped policy the per-item override wins
	 * over the inherited granularity set: {@link ConflictResolutionOverride#GRANULAR} forces the element's
	 * own key, {@link ConflictResolutionOverride#ENTITY} pins the element to the whole-entity key, and
	 * {@link ConflictResolutionOverride#INHERITED} (or the absence of any per-item override in global-backed
	 * mode) follows the resolved granularity set.
	 *
	 * @param granularPolicy the sub-entity refinement corresponding to the element being tested
	 * @param itemOverride   the element's per-item override, or null when none applies (global-backed mode,
	 *                       elements without an override object, or an element that could not be located)
	 * @return true if the element's own granular conflict key should be produced
	 */
	private boolean shouldEmitGranular(
		@Nonnull GranularConflictPolicy granularPolicy,
		@Nullable ConflictResolutionOverride itemOverride
	) {
		final ConflictResolution resolved = resolvedResolution();
		if (resolved.policy() != ConflictPolicy.ENTITY) {
			// a coarser scope or NONE dominates: no per-element keys are produced
			return false;
		}
		if (itemOverride == ConflictResolutionOverride.GRANULAR) {
			// the element explicitly opts into its own key regardless of the inherited set
			return true;
		}
		if (itemOverride == ConflictResolutionOverride.ENTITY) {
			// the element explicitly serializes on the whole entity: no per-element key
			return false;
		}
		if (itemOverride == null || itemOverride == ConflictResolutionOverride.INHERITED) {
			// global-backed mode with no per-item override, or an explicit inherit: follow the resolved set
			return resolved.granularity().contains(granularPolicy);
		}
		throw new GenericEvitaInternalError(
			"Unhandled conflict resolution override: " + itemOverride
		);
	}

	/**
	 * Returns the effective resolution governing the currently processed mutation. In global-backed mode
	 * this is the fixed resolution; in schema-aware mode it is the per-entity resolution cached by
	 * {@link #withEntityType(String, Integer, Function)} when an entity is in scope, falling back to the
	 * catalog-level resolution (catalog schema or engine default) when no entity is in scope.
	 *
	 * @return the effective {@link ConflictResolution}, never null
	 */
	@Nonnull
	private ConflictResolution resolvedResolution() {
		if (this.currentResolution != null) {
			// schema-aware mode with an entity in scope: the per-entity resolution was cached on entry
			return this.currentResolution;
		}
		final SchemaResolutionScope scope = this.schemaScope;
		if (scope == null) {
			// global-backed mode: the base resolution governs every entity type identically
			return this.baseResolution;
		}
		// schema-aware mode with no entity in scope: resolve at the catalog level
		return scope.catalogSchema().getConflictResolution().orElse(this.baseResolution);
	}

	/**
	 * Looks up the per-item {@link ConflictResolutionOverride} declared on the named entity attribute.
	 *
	 * @param attributeName the entity attribute name
	 * @return the declared override, or null when there is no entity schema in scope or the attribute is
	 *         not present in it
	 */
	@Nullable
	private ConflictResolutionOverride attributeOverride(@Nonnull String attributeName) {
		if (this.currentEntitySchema == null) {
			return null;
		}
		final Optional<? extends AttributeSchemaContract> attribute = this.currentEntitySchema.getAttribute(attributeName);
		return attribute
			.map(ConflictResolutionOverrideAwareSchemaContract::getConflictResolutionOverride)
			.orElse(null);
	}

	/**
	 * Looks up the per-item {@link ConflictResolutionOverride} declared on the named reference.
	 *
	 * @param referenceName the reference name
	 * @return the declared override, or null when there is no entity schema in scope or the reference is
	 *         not present in it
	 */
	@Nullable
	private ConflictResolutionOverride referenceOverride(@Nonnull String referenceName) {
		if (this.currentEntitySchema == null) {
			return null;
		}
		final Optional<ReferenceSchemaContract> reference = this.currentEntitySchema.getReference(referenceName);
		return reference
			.map(ConflictResolutionOverrideAwareSchemaContract::getConflictResolutionOverride)
			.orElse(null);
	}

	/**
	 * Looks up the per-item {@link ConflictResolutionOverride} declared on the named attribute nested in the
	 * named reference (the override controlling {@link GranularConflictPolicy#REFERENCE_ATTRIBUTE}).
	 *
	 * @param referenceName the reference name
	 * @param attributeName the reference attribute name
	 * @return the declared override, or null when there is no entity schema in scope or the reference /
	 *         reference attribute is not present in it
	 */
	@Nullable
	private ConflictResolutionOverride referenceAttributeOverride(
		@Nonnull String referenceName,
		@Nonnull String attributeName
	) {
		if (this.currentEntitySchema == null) {
			return null;
		}
		final Optional<ReferenceSchemaContract> reference = this.currentEntitySchema.getReference(referenceName);
		if (reference.isEmpty()) {
			return null;
		}
		final Optional<AttributeSchemaContract> attribute = reference.get().getAttribute(attributeName);
		return attribute
			.map(ConflictResolutionOverrideAwareSchemaContract::getConflictResolutionOverride)
			.orElse(null);
	}

	/**
	 * Looks up the per-item {@link ConflictResolutionOverride} declared on the named associated data.
	 *
	 * @param associatedDataName the associated data name
	 * @return the declared override, or null when there is no entity schema in scope or the associated data
	 *         is not present in it
	 */
	@Nullable
	private ConflictResolutionOverride associatedDataOverride(@Nonnull String associatedDataName) {
		if (this.currentEntitySchema == null) {
			return null;
		}
		final Optional<AssociatedDataSchemaContract> associatedData = this.currentEntitySchema.getAssociatedData(associatedDataName);
		return associatedData
			.map(ConflictResolutionOverrideAwareSchemaContract::getConflictResolutionOverride)
			.orElse(null);
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
	 * before execution and automatically cleared afterwards, even if an exception occurs. In schema-aware
	 * mode the effective entity-level resolution and entity schema are resolved once here and cached for
	 * the duration of the scope so the {@code shouldEmit*} predicates can consult them (and any per-item
	 * overrides) without re-resolving.
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
			final SchemaResolutionScope scope = this.schemaScope;
			if (scope != null) {
				// schema-aware mode: resolve the effective entity-level resolution once and cache it (along
				// with the entity schema) for per-item override lookups during this scope
				this.currentEntitySchema = scope.entitySchemaAccessor().apply(entityType);
				this.currentResolution = EffectiveConflictResolutionResolver.resolve(
					scope.catalogSchema(), this.currentEntitySchema, this.baseResolution
				);
			}
			return lambda.apply(this);
		} finally {
			this.entityType = null;
			this.entityPrimaryKey = null;
			this.currentEntitySchema = null;
			this.currentResolution = null;
		}
	}

	/**
	 * Immutable bundle of the schema inputs that enable per-entity, schema-aware conflict resolution. The
	 * two components are always supplied together, so carrying them as a single nullable field lets a single
	 * null-check narrow both to non-null at once.
	 *
	 * @param catalogSchema        the catalog schema whose resolution overrides the engine default
	 * @param entitySchemaAccessor accessor returning the entity schema for a given entity type (its result
	 *                             may be null when the type has no schema yet)
	 */
	private record SchemaResolutionScope(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull Function<String, EntitySchemaContract> entitySchemaAccessor
	) {
	}

}
