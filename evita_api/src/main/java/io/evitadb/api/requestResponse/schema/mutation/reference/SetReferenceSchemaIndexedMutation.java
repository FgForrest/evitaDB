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

package io.evitadb.api.requestResponse.schema.mutation.reference;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Mutation is responsible for setting value to a {@link ReferenceSchemaContract#isIndexed()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link ReferenceSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode(callSuper = true)
public class SetReferenceSchemaIndexedMutation
	extends AbstractModifyReferenceDataSchemaMutation
	implements CombinableLocalEntitySchemaMutation {
	@Serial private static final long serialVersionUID = -6023178895674039357L;
	@Getter @Nullable private final ScopedReferenceIndexType[] indexedInScopes;
	@Getter @Nullable private final ScopedReferenceIndexedComponents[] indexedComponentsInScopes;

	/**
	 * Verifies that making the reference non-indexed does not conflict with any filterable, unique,
	 * or sortable attributes that require an indexed reference to function.
	 *
	 * @param entitySchema    the entity schema containing the reference
	 * @param referenceSchema the reference schema whose attributes are being checked
	 * @param newIndexedScopes the NEW index types being set by the mutation (not the old ones)
	 */
	private static void verifyAttributeIndexRequirements(
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull Map<Scope, ReferenceIndexType> newIndexedScopes
	) {
		for (Scope scope : Scope.values()) {
			final ReferenceIndexType newIndexType = newIndexedScopes.getOrDefault(
				scope, ReferenceIndexType.NONE
			);
			for (AttributeSchemaContract attributeSchema : referenceSchema.getAttributes().values()) {
				if (attributeSchema.isFilterableInScope(scope) ||
					attributeSchema.isUniqueInScope(scope) ||
					attributeSchema.isSortableInScope(scope)) {
					final String type;
					if (attributeSchema.isFilterableInScope(scope)) {
						type = "filterable";
					} else if (attributeSchema.isUniqueInScope(scope)) {
						type = "unique";
					} else {
						type = "sortable";
					}
					if (newIndexType == ReferenceIndexType.NONE) {
						// new index type is NONE in the scope, but attribute is indexed
						// this is not allowed because it would prevent filtering/sorting
						throw new InvalidSchemaMutationException(
							"Cannot make reference schema `" +
								referenceSchema.getName() + "` of entity `" +
								entitySchema.getName() + "` " +
								"non-indexed if there is a single " + type +
								" attribute in scope `" + scope + "`! " +
								"Found " + type + " attribute definition `" +
								attributeSchema.getName() + "`."
						);
					}
				}
			}
		}
	}

	/**
	 * Creates mutation that controls the indexed flag of the reference schema using a simple
	 * boolean (applied to the default scope). Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable Boolean indexed
	) {
		this(
			name,
			indexed == null ? null : (indexed ? Scope.DEFAULT_SCOPES : Scope.NO_SCOPE)
		);
	}

	/**
	 * Creates mutation that controls indexed flag per scope. Each specified scope gets
	 * {@link ReferenceIndexType#FOR_FILTERING}. Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable Scope[] indexedInScopes
	) {
		this(
			name,
			indexedInScopes == null
				? null
				: Arrays.stream(indexedInScopes)
					.map(scope -> new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING))
					.toArray(ScopedReferenceIndexType[]::new),
			null
		);
	}

	/**
	 * Creates mutation that controls indexed flag with detailed per-scope index type configuration.
	 * Null means inherited from the reflected reference.
	 */
	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable ScopedReferenceIndexType[] indexedInScopes
	) {
		this(name, indexedInScopes, null);
	}

	/**
	 * Creates mutation that controls indexed flag with detailed per-scope index type configuration
	 * and also specifies which reference components are indexed per scope.
	 * Null means inherited from the reflected reference.
	 */
	@SerializableCreator
	public SetReferenceSchemaIndexedMutation(
		@Nonnull String name,
		@Nullable ScopedReferenceIndexType[] indexedInScopes,
		@Nullable ScopedReferenceIndexedComponents[] indexedComponentsInScopes
	) {
		super(name);
		// Deliberately preserve null — for reflected references, null signals that indexed scopes
		// are inherited from the reflected reference (see ReflectedReferenceSchemaBuilder).
		// This is distinct from EMPTY which means "explicitly not indexed in any scope".
		// Downstream code (getIndexed, combineWith, toString, mutate) relies on the null vs EMPTY
		// distinction to differentiate inherited from explicitly-empty.
		this.indexedInScopes = indexedInScopes;
		if (indexedComponentsInScopes != null) {
			this.indexedComponentsInScopes = indexedComponentsInScopes;
		} else if (indexedInScopes == null) {
			// Both scopes and components are inherited — use EMPTY to signal "no explicit override"
			// rather than null, because components inheritance is always tied to scopes inheritance
			this.indexedComponentsInScopes = ScopedReferenceIndexedComponents.EMPTY;
		} else {
			// Deliberately preserve null — for reflected references, null signals that indexed
			// components are inherited from the reflected reference
			// (see ReflectedReferenceSchemaBuilder#withIndexedComponentsInherited).
			// For non-reflected references, null is resolved to default components at mutation
			// application time in the mutate() method, so the semantics are preserved for both cases.
			this.indexedComponentsInScopes = null;
		}
	}

	/**
	 * Returns the indexed flag for the default scope, or null when the value is inherited.
	 */
	@Nullable
	public Boolean getIndexed() {
		// null indexedInScopes means scopes are inherited from the reflected reference
		if (this.indexedInScopes == null) {
			return null;
		} else {
			return Arrays.stream(this.indexedInScopes)
				.anyMatch(it -> it.scope() == Scope.DEFAULT_SCOPE && it.indexType() != ReferenceIndexType.NONE);
		}
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalEntitySchemaMutation> combineWith(
		@Nonnull CatalogSchemaContract currentCatalogSchema,
		@Nonnull EntitySchemaContract currentEntitySchema,
		@Nonnull LocalEntitySchemaMutation existingMutation
	) {
		if (existingMutation instanceof SetReferenceSchemaIndexedMutation theExistingMutation
			&& this.name.equals(theExistingMutation.getName())) {
			// null indexedInScopes = inherited from reflected reference;
			// when either mutation uses inheritance, no scope merging is needed
			if (this.indexedInScopes == null) {
				// the latest mutation says "inherit" — this overrides any prior explicit scopes
				return new MutationCombinationResult<>(null, this);
			} else if (theExistingMutation.indexedInScopes == null) {
				// existing was inherited, but this mutation overrides with explicit scopes
				return new MutationCombinationResult<>(null, this);
			} else {
				final Map<Scope, ReferenceIndexType> existingIndexedScopes =
					Arrays.stream(theExistingMutation.indexedInScopes)
						.collect(
							() -> new EnumMap<>(Scope.class),
							(map, scopedIndexType) -> map.put(scopedIndexType.scope(), scopedIndexType.indexType()),
							EnumMap::putAll
						);
				for (ScopedReferenceIndexType indexedInScope : this.indexedInScopes) {
					existingIndexedScopes.put(indexedInScope.scope(), indexedInScope.indexType());
				}

				// merge indexed components across both mutations: newer mutation takes precedence
				// null means "inherited" — if the newer mutation sets null, the result is inherited
				final ScopedReferenceIndexedComponents[] mergedComponents;
				if (this.indexedComponentsInScopes == null) {
					mergedComponents = null;
				} else {
					final EnumMap<Scope, ReferenceIndexedComponents[]> componentMap = new EnumMap<>(Scope.class);
					if (theExistingMutation.indexedComponentsInScopes != null) {
						for (final ScopedReferenceIndexedComponents existing : theExistingMutation.indexedComponentsInScopes) {
							componentMap.put(existing.scope(), existing.indexedComponents());
						}
					}
					for (final ScopedReferenceIndexedComponents current : this.indexedComponentsInScopes) {
						componentMap.put(current.scope(), current.indexedComponents());
					}
					mergedComponents = componentMap.entrySet()
						.stream()
						.map(entry -> new ScopedReferenceIndexedComponents(entry.getKey(), entry.getValue()))
						.toArray(ScopedReferenceIndexedComponents[]::new);
				}

				// strip components for scopes that are now NONE after merging
				final ScopedReferenceIndexedComponents[] filteredComponents =
					ReferenceSchema.filterComponentsArrayForNoneScopes(mergedComponents, existingIndexedScopes);

				final SetReferenceSchemaIndexedMutation combinedMutation = new SetReferenceSchemaIndexedMutation(
					this.name,
					existingIndexedScopes
						.entrySet()
						.stream()
						.map(entry -> new ScopedReferenceIndexType(entry.getKey(), entry.getValue()))
						.toArray(ScopedReferenceIndexType[]::new),
					filteredComponents
				);
				return new MutationCombinationResult<>(null, combinedMutation);
			}
		} else if (existingMutation instanceof CreateReferenceSchemaMutation createMutation
			&& this.name.equals(createMutation.getName())
			&& this.indexedInScopes != null) {
			// Absorb into the Create mutation using merge semantics — the Set mutation's
			// scopes are merged into the Create's existing scopes (matching Set+Set merge)
			final ScopedReferenceIndexType[] mergedScopes =
				mergeIndexedScopes(createMutation.getIndexedInScopes(), this.indexedInScopes);
			final ScopedReferenceIndexedComponents[] mergedComponents =
				mergeIndexedComponents(
					createMutation.getIndexedComponentsInScopes(),
					this.indexedComponentsInScopes,
					mergedScopes
				);
			return new MutationCombinationResult<>(
				new CreateReferenceSchemaMutation(
					createMutation.getName(),
					createMutation.getDescription(),
					createMutation.getDeprecationNotice(),
					createMutation.getCardinality(),
					createMutation.getReferencedEntityType(),
					createMutation.isReferencedEntityTypeManaged(),
					createMutation.getReferencedGroupType(),
					createMutation.isReferencedGroupTypeManaged(),
					mergedScopes,
					mergedComponents,
					createMutation.getFacetedInScopes()
				)
			);
		} else {
			// Note: we intentionally do NOT absorb into CreateReflectedReferenceSchemaMutation.
			// Reflected references have complex inheritance semantics for indexed components —
			// null components means "inherited from the reflected reference" — which cannot be
			// faithfully represented in CreateReflected when scopes are explicit (the _internalBuild
			// method resolves null components + explicit scopes to defaults, losing inheritance).
			// Leaving the Set as a separate mutation preserves correct behavior via Set.mutate().
			return null;
		}
	}

	@Nonnull
	@Override
	public ReferenceSchemaContract mutate(
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nonnull ConsistencyChecks consistencyChecks
	) {
		Assert.isPremiseValid(referenceSchema != null, "Reference schema is mandatory!");
		// null indexedInScopes = inherited — produces an empty map (actual scopes resolved elsewhere)
		final EnumMap<Scope, ReferenceIndexType> indexedScopes = this.indexedInScopes == null ?
			new EnumMap<>(Scope.class) :
			Arrays.stream(this.indexedInScopes)
				.collect(
					() -> new EnumMap<>(Scope.class),
					(map, scopedIndexType) -> map.put(scopedIndexType.scope(), scopedIndexType.indexType()),
					EnumMap::putAll
				);
		if (referenceSchema instanceof ReflectedReferenceSchema reflectedReferenceSchema) {
			ReflectedReferenceSchema result = reflectedReferenceSchema;
			boolean scopesChanged = false;
			if (hasIndexedScopesChanged(reflectedReferenceSchema, indexedScopes)) {
				result = (ReflectedReferenceSchema) result.withIndexed(this.indexedInScopes);
				scopesChanged = true;
			}
			if (hasIndexedComponentsChanged(reflectedReferenceSchema)) {
				// only filter when scopes are explicit (non-null); when inherited, components pass
				// through unfiltered and are resolved later against the actual inherited scopes
				final ScopedReferenceIndexedComponents[] componentsToApply =
					this.indexedInScopes != null
						? ReferenceSchema.filterComponentsArrayForNoneScopes(
							this.indexedComponentsInScopes, indexedScopes)
						: this.indexedComponentsInScopes;
				result = (ReflectedReferenceSchema) result.withIndexedComponents(componentsToApply);
			} else if (scopesChanged && this.indexedInScopes != null
				&& !result.isIndexedComponentsInherited()) {
				// scopes changed but components didn't — still strip components for newly-NONE scopes
				final Map<Scope, Set<ReferenceIndexedComponents>> currentComponents =
					result.getIndexedComponentsInScopes();
				final Map<Scope, Set<ReferenceIndexedComponents>> filtered =
					ReferenceSchema.filterComponentsForNoneScopes(currentComponents, indexedScopes);
				if (filtered != currentComponents) {
					final ScopedReferenceIndexedComponents[] filteredArray =
						new ScopedReferenceIndexedComponents[filtered.size()];
					int i = 0;
					for (Map.Entry<Scope, Set<ReferenceIndexedComponents>> entry : filtered.entrySet()) {
						filteredArray[i++] = new ScopedReferenceIndexedComponents(
							entry.getKey(),
							entry.getValue().toArray(ReferenceIndexedComponents.EMPTY)
						);
					}
					result = (ReflectedReferenceSchema) result.withIndexedComponents(filteredArray);
				}
			}
			return result;
		} else {
			// strip components for NONE-indexed scopes before building the schema
			final ScopedReferenceIndexedComponents[] filteredComponentsArray =
				ReferenceSchema.filterComponentsArrayForNoneScopes(
					this.indexedComponentsInScopes, indexedScopes
				);
			final Map<Scope, Set<ReferenceIndexedComponents>> indexedComponents =
				filteredComponentsArray != null
					? ReferenceSchema.toIndexedComponentsEnumMap(filteredComponentsArray)
					: ReferenceSchema.defaultIndexedComponents(indexedScopes);

			if (indexedScopes.equals(referenceSchema.getReferenceIndexTypeInScopes()) &&
				indexedComponents.equals(referenceSchema.getIndexedComponentsInScopes())) {
				// schema is already indexed with same components
				return referenceSchema;
			} else {
				if (consistencyChecks == ConsistencyChecks.APPLY) {
					verifyAttributeIndexRequirements(entitySchema, referenceSchema, indexedScopes);
				}

				return ReferenceSchema._internalBuild(
					this.name,
					referenceSchema.getNameVariants(),
					referenceSchema.getDescription(),
					referenceSchema.getDeprecationNotice(),
					referenceSchema.getCardinality(),
					referenceSchema.getReferencedEntityType(),
					referenceSchema.isReferencedEntityTypeManaged()
						? Collections.emptyMap()
						: referenceSchema.getEntityTypeNameVariants(s -> null),
					referenceSchema.isReferencedEntityTypeManaged(),
					referenceSchema.getReferencedGroupType(),
					referenceSchema.isReferencedGroupTypeManaged()
						? Collections.emptyMap()
						: referenceSchema.getGroupTypeNameVariants(s -> null),
					referenceSchema.isReferencedGroupTypeManaged(),
					indexedScopes,
					indexedComponents,
					referenceSchema.getFacetedInScopes(),
					referenceSchema.getFacetedPartiallyInScopes(),
					referenceSchema.getAttributes(),
					referenceSchema.getSortableAttributeCompounds()
				);
			}
		}
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		Assert.isPremiseValid(entitySchema != null, "Entity schema is mandatory!");
		final Optional<ReferenceSchemaContract> existingReferenceSchema = entitySchema.getReference(this.name);
		if (existingReferenceSchema.isEmpty()) {
			// the reference is missing
			throw new InvalidSchemaMutationException(
				"The reference `" + this.name + "` is not defined in entity `" + entitySchema.getName() + "` schema!"
			);
		} else {
			final ReferenceSchemaContract theSchema = existingReferenceSchema.get();
			final ReferenceSchemaContract updatedReferenceSchema = mutate(
				entitySchema, theSchema, ConsistencyChecks.SKIP);
			return replaceReferenceSchema(
				entitySchema, theSchema, updatedReferenceSchema
			);
		}
	}

	@Override
	public String toString() {
		// null = inherited from reflected reference, EMPTY = explicitly not indexed
		final String indexedDescription;
		if (this.indexedInScopes == null) {
			indexedDescription = "(inherited)";
		} else if (ArrayUtils.isEmpty(this.indexedInScopes)) {
			indexedDescription = "(not indexed)";
		} else {
			indexedDescription = "(" +
				Arrays.stream(this.indexedInScopes)
					.map(it -> it.scope().name() + ": " + it.indexType().name())
					.collect(Collectors.joining(", ")) +
				")";
		}
		final String componentsDescription;
		if (this.indexedComponentsInScopes == null) {
			componentsDescription = "";
		} else if (ArrayUtils.isEmpty(this.indexedComponentsInScopes)) {
			componentsDescription = ", components=(none)";
		} else {
			componentsDescription = ", components=(" +
				Arrays.stream(this.indexedComponentsInScopes)
					.map(it -> it.scope().name() + ": " + Arrays.toString(it.indexedComponents()))
					.collect(Collectors.joining(", ")) +
				")";
		}
		return "Set entity reference `" + this.name + "` schema: " +
			"indexed=" + indexedDescription + componentsDescription;
	}

	/**
	 * Checks whether the indexed scopes setting has changed
	 * for a reflected reference schema. Compares the current
	 * inheritance/explicit state with this mutation's target.
	 *
	 * @param schema           the current reflected reference schema
	 * @param newIndexedScopes the new indexed scopes map
	 * @return true if the indexed scopes setting has changed
	 */
	private boolean hasIndexedScopesChanged(
		@Nonnull ReflectedReferenceSchema schema,
		@Nonnull Map<Scope, ReferenceIndexType> newIndexedScopes
	) {
		if (schema.isIndexedInherited()) {
			// was inherited — changed only if new value is explicit
			return this.indexedInScopes != null;
		}
		// was explicit — changed if switching to inherited or scopes differ
		return this.indexedInScopes == null || !schema.getReferenceIndexTypeInScopes().equals(newIndexedScopes);
	}

	/**
	 * Checks whether the indexed components setting has changed
	 * for a reflected reference schema. Compares the current
	 * inheritance/explicit state with this mutation's target.
	 *
	 * @param schema the current reflected reference schema
	 * @return true if the indexed components setting has changed
	 */
	private boolean hasIndexedComponentsChanged(
		@Nonnull ReflectedReferenceSchema schema
	) {
		if (this.indexedComponentsInScopes == null) {
			// switching to inherited — changed only if was explicit
			return !schema.isIndexedComponentsInherited();
		}
		if (schema.isIndexedComponentsInherited()) {
			// was inherited, now explicit — always changed
			return true;
		}
		// both explicit — compare actual component maps
		final Map<Scope, Set<ReferenceIndexedComponents>> newComponents = ReferenceSchema.toIndexedComponentsEnumMap(
			this.indexedComponentsInScopes
		);
		return !schema.getIndexedComponentsInScopes().equals(newComponents);
	}

	/**
	 * Merges new indexed scopes into existing ones. New scopes take precedence
	 * for overlapping entries, matching the Set+Set merge semantics.
	 *
	 * @param existing the existing indexed scopes from the Create mutation
	 * @param incoming the new indexed scopes from this Set mutation
	 * @return merged array of scoped index types
	 */
	@Nonnull
	private static ScopedReferenceIndexType[] mergeIndexedScopes(
		@Nonnull ScopedReferenceIndexType[] existing,
		@Nonnull ScopedReferenceIndexType[] incoming
	) {
		final EnumMap<Scope, ReferenceIndexType> merged = new EnumMap<>(Scope.class);
		for (ScopedReferenceIndexType sit : existing) {
			merged.put(sit.scope(), sit.indexType());
		}
		for (ScopedReferenceIndexType sit : incoming) {
			merged.put(sit.scope(), sit.indexType());
		}
		return merged.entrySet()
			.stream()
			.map(e -> new ScopedReferenceIndexType(e.getKey(), e.getValue()))
			.toArray(ScopedReferenceIndexType[]::new);
	}

	/**
	 * Merges new indexed components into existing ones, stripping components
	 * for scopes that are now {@link ReferenceIndexType#NONE}.
	 *
	 * @param existing     the existing components from the Create mutation
	 * @param incoming     the new components from this Set mutation (may be null)
	 * @param mergedScopes the already-merged index type scopes for NONE filtering
	 * @return merged and filtered components array
	 */
	@Nullable
	private static ScopedReferenceIndexedComponents[] mergeIndexedComponents(
		@Nonnull ScopedReferenceIndexedComponents[] existing,
		@Nullable ScopedReferenceIndexedComponents[] incoming,
		@Nonnull ScopedReferenceIndexType[] mergedScopes
	) {
		if (incoming == null) {
			// null incoming = "use defaults" — return null so the Create constructor
			// auto-derives default components from the merged scopes
			return null;
		}
		final EnumMap<Scope, ReferenceIndexedComponents[]> componentMap = new EnumMap<>(Scope.class);
		for (ScopedReferenceIndexedComponents ec : existing) {
			componentMap.put(ec.scope(), ec.indexedComponents());
		}
		for (ScopedReferenceIndexedComponents ic : incoming) {
			componentMap.put(ic.scope(), ic.indexedComponents());
		}
		final ScopedReferenceIndexedComponents[] merged = componentMap.entrySet()
			.stream()
			.map(e -> new ScopedReferenceIndexedComponents(e.getKey(), e.getValue()))
			.toArray(ScopedReferenceIndexedComponents[]::new);
		final EnumMap<Scope, ReferenceIndexType> scopeMap = new EnumMap<>(Scope.class);
		for (ScopedReferenceIndexType sit : mergedScopes) {
			scopeMap.put(sit.scope(), sit.indexType());
		}
		return ReferenceSchema.filterComponentsArrayForNoneScopes(merged, scopeMap);
	}
}
