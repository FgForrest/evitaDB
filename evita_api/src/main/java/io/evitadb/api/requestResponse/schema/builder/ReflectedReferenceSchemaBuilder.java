/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Internal {@link ReflectedReferenceSchema} builder used solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class ReflectedReferenceSchemaBuilder
	extends AbstractReferenceSchemaBuilder<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder, ReflectedReferenceSchema>
	implements ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder {
	@Serial private static final long serialVersionUID = 3141592653589793238L;

	/**
	 * Creates a new builder for a reflected reference schema. When `createNew` is true,
	 * a {@link CreateReflectedReferenceSchemaMutation} is automatically emitted with the current
	 * schema state (or inherited defaults when no overrides exist). Pre-existing mutations
	 * targeting this reference (excluding create/remove) are also replayed.
	 */
	ReflectedReferenceSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReflectedReferenceSchemaContract existingSchema,
		@Nonnull String name,
		@Nonnull String entityType,
		@Nonnull String reflectedReferenceName,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		boolean createNew
	) {
		super(
			catalogSchema, entitySchema,
			existingSchema == null ?
				ReflectedReferenceSchema._internalBuild(name, entityType, reflectedReferenceName) :
				(ReflectedReferenceSchema) existingSchema
		);
		if (createNew) {
			this.mutations.add(
				new CreateReflectedReferenceSchemaMutation(
					this.baseSchema.getName(),
					this.baseSchema.isDescriptionInherited() ? null : this.baseSchema.getDescription(),
					this.baseSchema.isDeprecatedInherited() ? null : this.baseSchema.getDeprecationNotice(),
					this.baseSchema.isCardinalityInherited() ? null : this.baseSchema.getCardinality(),
					entityType,
					reflectedReferenceName,
					this.baseSchema.isIndexedInherited() ?
						null :
						this.baseSchema.getReferenceIndexTypeInScopes()
							.entrySet()
							.stream()
							.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
							.toArray(ScopedReferenceIndexType[]::new),
					this.baseSchema.isIndexedComponentsInherited()
						? null
						: this.baseSchema.getIndexedComponentsInScopes()
							.entrySet()
							.stream()
							.map(
								it -> new ScopedReferenceIndexedComponents(
									it.getKey(),
									it.getValue().toArray(ReferenceIndexedComponents.EMPTY)
								)
							)
							.toArray(ScopedReferenceIndexedComponents[]::new),
					this.baseSchema.isFacetedInherited() ?
						null : Arrays.stream(Scope.values()).filter(this.baseSchema::isFacetedInScope).toArray(Scope[]::new),
					this.baseSchema.getAttributesInheritanceBehavior(),
					this.baseSchema.getAttributeInheritanceFilter()
				)
			);
		}
		mutations.stream()
			.filter(
				it -> it instanceof ReferenceSchemaMutation referenceSchemaMutation &&
						(name.equals(referenceSchemaMutation.getName()) &&
						!(referenceSchemaMutation instanceof CreateReflectedReferenceSchemaMutation)) &&
						!(referenceSchemaMutation instanceof RemoveReferenceSchemaMutation)
			)
			.forEach(this.mutations::add);
	}

	/**
	 * Note: setting null to the description will cause the description is inherited from the original reference.
	 * The description cannot be reset to NULL on the reflected reference if the original one has description set.
	 *
	 * This override is required to prevent Lombok's `@Delegate` from generating a conflicting delegation
	 * for the `withDescription` method declared on `ReflectedReferenceSchema` DTO.
	 *
	 * @param description new value of description
	 * @return this
	 */
	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder withDescription(@Nullable String description) {
		return super.withDescription(description);
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withDescriptionInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDescriptionMutation(getName(), null)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withDeprecatedInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), null)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withCardinality(@Nonnull Cardinality cardinality) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaCardinalityMutation(getName(), cardinality)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withCardinalityInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaCardinalityMutation(getName(), null)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withAttributesInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					this.getName(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withoutAttributesInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					this.getName(), AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withAttributesInherited(@Nonnull String... attributeNames) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					this.getName(), AttributeInheritanceBehavior.INHERIT_ONLY_SPECIFIED, attributeNames
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withAttributesInheritedExcept(@Nonnull String... attributeNames) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReflectedReferenceAttributeInheritanceSchemaMutation(
					this.getName(), AttributeInheritanceBehavior.INHERIT_ALL_EXCEPT, attributeNames
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withFacetedInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(getName(), (Scope[]) null)
			)
		);
		return this;
	}

	/**
	 * Note: this method will cause the deprecation status is inherited from the original reference. The deprecation
	 * cannot be reset to not deprecated on the reflected reference if the original one is deprecated.
	 *
	 * @return this
	 */
	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder notDeprecatedAnymore() {
		return withDeprecatedInherited();
	}

	/**
	 * Note: this method will behave differently depending on input:
	 *
	 * - if the input is NULL, the settings are inherited from the original reference
	 * - if the input contains any scopes, the reference is indexed in these scopes
	 *
	 * Input cannot be empty array, in order reflected references can be propagated, they need to be indexed.
	 */
	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder indexedInScope(@Nullable Scope... inScope) {
		Assert.isTrue(
			!(inScope != null && inScope.length == 0),
			() -> new InvalidSchemaMutationException("Reflected references must be indexed (otherwise we wouldn't be able to propagate the reflections)!")
		);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(getName(), inScope)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder nonIndexed() {
		if (isReflectedReferenceAvailable()) {
			return nonIndexed(Scope.DEFAULT_SCOPE);
		} else {
			throw new InvalidSchemaMutationException(
				"Reflected references must be indexed (otherwise we wouldn't be able to propagate the reflections)!"
			);
		}
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder nonIndexed(@Nullable Scope... inScope) {
		Assert.isTrue(
			!isIndexedInherited() || isReflectedReferenceAvailable(),
			() -> new InvalidSchemaMutationException("Cannot update non-indexed scopes on inherited reference schema when the referenced schema is not available!")
		);

		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		final ScopedReferenceIndexType[] newScopes = getReferenceIndexTypeInScopes()
			.entrySet()
			.stream()
			.filter(it -> !excludedScopes.contains(it.getKey()))
			.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
			.toArray(ScopedReferenceIndexType[]::new);

		Assert.isTrue(
			newScopes.length > 0,
			() -> new InvalidSchemaMutationException("Reflected references must be indexed (otherwise we wouldn't be able to propagate the reflections)!")
		);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(getName(), newScopes)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder facetedInScope(@Nonnull Scope... inScope) {
		// breaking inheritance: the mutation carries the complete explicit state from scratch
		final EnumSet<Scope> newScopeSet = ArrayUtils.toEnumSet(Scope.class, inScope);
		// when breaking inheritance, facetedPartially starts empty; when already explicit, filter
		final ScopedFacetedPartially[] filteredPartially;
		if (isFacetedInherited()) {
			filteredPartially = ScopedFacetedPartially.EMPTY;
		} else {
			filteredPartially = toScopedFacetedPartiallyArray(filterPartiallyToScopes(newScopeSet));
		}
		final boolean reflectedReferenceAvailable = isReflectedReferenceAvailable();
		final boolean indexedInherited = isIndexedInherited();
		final boolean allInformationPresent = reflectedReferenceAvailable || !indexedInherited;
		if (allInformationPresent && Arrays.stream(inScope).allMatch(this::isIndexedInScope)) {
			// just update the faceted scopes
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaFacetedMutation(getName(), inScope, filteredPartially)
				)
			);
		} else {
			// update both indexed and faceted scopes
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaIndexedMutation(
						getName(),
						Arrays.stream(Scope.values())
							.filter(scope -> newScopeSet.contains(scope)
								|| (allInformationPresent && this.isIndexedInScope(scope)))
							.toArray(Scope[]::new)
					),
					new SetReferenceSchemaFacetedMutation(getName(), inScope, filteredPartially)
				)
			);
		}
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder facetedPartiallyInScope(
		@Nonnull Scope scope,
		@Nonnull Expression expression
	) {
		// compute complete state: when inherited, start from scratch; when explicit, build on top
		final EnumSet<Scope> allScopes;
		final Map<Scope, Expression> allPartially;
		if (isFacetedInherited()) {
			// breaking inheritance — start from scratch with just this scope
			allScopes = EnumSet.of(scope);
			allPartially = new EnumMap<>(Scope.class);
		} else {
			// already explicit — accumulate on top of current state
			final Set<Scope> currentFacetedScopes = this.getFacetedInScopes();
			allScopes = currentFacetedScopes.isEmpty()
				? EnumSet.noneOf(Scope.class) : EnumSet.copyOf(currentFacetedScopes);
			allScopes.add(scope);
			final Map<Scope, Expression> currentPartially = this.getFacetedPartiallyInScopes();
			allPartially = currentPartially.isEmpty()
				? new EnumMap<>(Scope.class) : new EnumMap<>(currentPartially);
		}
		allPartially.put(scope, expression);
		final Scope[] completeFacetedScopes = allScopes.toArray(Scope[]::new);
		final ScopedFacetedPartially[] completePartially = toScopedFacetedPartiallyArray(allPartially);
		final boolean reflectedReferenceAvailable = isReflectedReferenceAvailable();
		final boolean indexedInherited = isIndexedInherited();
		final boolean allInformationPresent = reflectedReferenceAvailable || !indexedInherited;
		if (allInformationPresent && this.isIndexedInScope(scope)) {
			// scope is already indexed — just emit the faceted mutation
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaFacetedMutation(
						getName(), completeFacetedScopes, completePartially
					)
				)
			);
		} else {
			// auto-promote to indexed before enabling faceting
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaIndexedMutation(
						getName(),
						Arrays.stream(Scope.values())
							.filter(s -> s == scope
								|| (allInformationPresent && this.isIndexedInScope(s)))
							.toArray(Scope[]::new)
					),
					new SetReferenceSchemaFacetedMutation(
						getName(), completeFacetedScopes, completePartially
					)
				)
			);
		}
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder nonFaceted() {
		if (isReflectedReferenceAvailable()) {
			return nonFaceted(Scope.DEFAULT_SCOPE);
		} else {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaFacetedMutation(
						getName(), Scope.NO_SCOPE, ScopedFacetedPartially.EMPTY
					)
				)
			);
			return this;
		}
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder nonFaceted(@Nonnull Scope... inScope) {
		Assert.isTrue(
			!isFacetedInherited() || isReflectedReferenceAvailable(),
			() -> new InvalidSchemaMutationException(
				"Cannot update non-indexed scopes on inherited reference schema "
					+ "when the referenced schema is not available!"
			)
		);

		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		final Scope[] remainingScopes = getFacetedInScopes().stream()
			.filter(it -> !excludedScopes.contains(it))
			.toArray(Scope[]::new);
		return applyNonFacetedMutation(remainingScopes);
	}

	/**
	 * Overrides the base implementation to handle reflected reference null semantics.
	 * For reflected references, passing `null` for `facetedInScopes` means "inherit from
	 * original" — so we must always pass the current explicit scopes to preserve them.
	 * When inherited, we pass `null` to keep inheritance intact.
	 */
	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder nonFacetedPartially(@Nonnull Scope... inScope) {
		if (isFacetedInherited()) {
			// when inherited, clearing a partial expression should stay inherited
			// but remove the partial expression for the specified scopes
			final EnumSet<Scope> clearedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
			final Map<Scope, Expression> currentPartially = this.getFacetedPartiallyInScopes();
			final Map<Scope, Expression> remaining = currentPartially.isEmpty()
				? new EnumMap<>(Scope.class) : new EnumMap<>(currentPartially);
			for (final Scope scope : clearedScopes) {
				remaining.remove(scope);
			}
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaFacetedMutation(
						getName(), null, toScopedFacetedPartiallyArray(remaining)
					)
				)
			);
		} else {
			// when explicit, use the base implementation which reads the complete state
			super.nonFacetedPartially(inScope);
		}
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withIndexedComponentsInherited() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(
					getName(),
					this.getReferenceIndexTypeInScopes()
						.entrySet()
						.stream()
						.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
						.toArray(ScopedReferenceIndexType[]::new),
					null
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<AttributeSchemaEditor.AttributeSchemaBuilder> whichIs
	) {
		final ReflectedReferenceSchema instance = this.toInstanceInternal();
		final AttributeSchemaContract existingAttribute = instance.getDeclaredAttribute(attributeName).orElse(null);
		final AttributeSchemaBuildResult result = buildAttributeSchema(attributeName, ofType, existingAttribute, whichIs);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			instance.isReflectedReferenceAvailable() ?
				instance.getAttributes().values() :
				instance.getDeclaredAttributes().values(),
			instance.isReflectedReferenceAvailable() ?
				instance.getSortableAttributeCompounds().values() :
				instance.getDeclaredSortableAttributeCompounds().values(),
			result.schema()
		);

		addAttributeMutationsIfChanged(existingAttribute, result.schema(), result.builder());
		return this;
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration and performs
	 * consistency checks.
	 */
	@Nonnull
	public ReferenceSchemaBuilderResult toResult() {
		final Collection<LocalEntitySchemaMutation> mutations = toMutation();
		// and now rebuild the schema from scratch including consistency checks, if available
		ReflectedReferenceSchema currentSchema = this.baseSchema;
		for (LocalEntitySchemaMutation mutation : mutations) {
			currentSchema = Objects.requireNonNull(
				(ReflectedReferenceSchema) ((ReferenceSchemaMutation) mutation).mutate(
					this.entitySchema, currentSchema,
					currentSchema.isReflectedReferenceAvailable() ? ConsistencyChecks.APPLY : ConsistencyChecks.SKIP
				)
			);
		}
		return new ReferenceSchemaBuilderResult(currentSchema, mutations);
	}

	@Nonnull
	@Override
	protected ReflectedReferenceSchema mutateSchema(
		@Nonnull ReflectedReferenceSchema currentSchema,
		@Nonnull LocalEntitySchemaMutation mutation
	) {
		final ReflectedReferenceSchema result = (ReflectedReferenceSchema)
			((ReferenceSchemaMutation) mutation).mutate(
				this.entitySchema, currentSchema, ReferenceSchemaMutator.ConsistencyChecks.SKIP
			);
		if (result == null) {
			throw new GenericEvitaInternalError("Reflected reference unexpectedly removed from inside!");
		}
		return result;
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = ReflectedReferenceSchema.class)
	private ReflectedReferenceSchema toInstanceInternal() {
		return toInstance();
	}

}
