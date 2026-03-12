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
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.ReferenceIndexedComponents;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedFacetedPartially;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Abstract parent for builders that produce {@link ReferenceSchemaContract} or its extensions.
 * Contains shared fields, mutation plumbing, and identical methods used by both
 * {@link ReferenceSchemaBuilder} and {@link ReflectedReferenceSchemaBuilder}.
 *
 * @param <T> self-type for fluent builder returns (bounded by the editor interface)
 * @param <S> the concrete schema type produced by this builder
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@SuppressWarnings("unchecked")
public abstract sealed class AbstractReferenceSchemaBuilder<
	T extends ReferenceSchemaEditor<T>,
	S extends ReferenceSchemaContract
	>
	implements ReferenceSchemaEditor<T>, InternalSchemaBuilderHelper
	permits ReferenceSchemaBuilder, ReflectedReferenceSchemaBuilder {
	@Serial private static final long serialVersionUID = -6435272035844056999L;
	@Nonnull protected final S baseSchema;
	@Nonnull protected final CatalogSchemaContract catalogSchema;
	@Nonnull protected final EntitySchemaContract entitySchema;
	@Nonnull protected final LinkedList<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	@Nonnull protected MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	protected int lastMutationReflectedInSchema = 0;
	@Nullable protected S updatedSchema;

	/**
	 * Constructs a new abstract reference schema builder.
	 *
	 * @param catalogSchema the catalog schema context
	 * @param entitySchema  the entity schema context
	 * @param baseSchema    the base schema to build upon
	 */
	AbstractReferenceSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull S baseSchema
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = baseSchema;
	}

	@Override
	@Nonnull
	public T withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDescriptionMutation(getName(), description)
			)
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T indexedWithComponentsInScope(
		@Nonnull Scope scope,
		@Nonnull ReferenceIndexedComponents... components
	) {
		this.updatedSchemaDirty = indexedWithComponentsInScope(
			this.catalogSchema, this.entitySchema, this.mutations,
			this.updatedSchemaDirty, getName(), getReferenceIndexTypeInScopes(),
			scope, components
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T facetedPartiallyInScope(
		@Nonnull Scope scope,
		@Nonnull Expression expression
	) {
		// compute complete state: current faceted scopes + new scope, current expressions + new entry
		final S current = toInstance();
		final Set<Scope> currentFacetedScopes = current.getFacetedInScopes();
		final EnumSet<Scope> allScopes = currentFacetedScopes.isEmpty()
			? EnumSet.noneOf(Scope.class) : EnumSet.copyOf(currentFacetedScopes);
		allScopes.add(scope);
		final Map<Scope, Expression> currentPartially = current.getFacetedPartiallyInScopes();
		final Map<Scope, Expression> allPartially = currentPartially.isEmpty()
			? new EnumMap<>(Scope.class) : new EnumMap<>(currentPartially);
		allPartially.put(scope, expression);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(
					getName(),
					allScopes.toArray(Scope[]::new),
					toScopedFacetedPartiallyArray(allPartially)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T nonFacetedPartially(@Nonnull Scope... inScope) {
		// compute complete state: current scopes unchanged, current expressions minus cleared scopes
		final S current = toInstance();
		final EnumSet<Scope> clearedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		final Map<Scope, Expression> currentPartially = current.getFacetedPartiallyInScopes();
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
					getName(),
					current.getFacetedInScopes().toArray(Scope[]::new),
					toScopedFacetedPartiallyArray(remaining)
				)
			)
		);
		return (T) this;
	}

	/**
	 * Computes the filtered `facetedPartially` array for the given remaining scopes and emits
	 * a {@link SetReferenceSchemaFacetedMutation}. This is the shared tail of the `nonFaceted`
	 * methods in both concrete builders.
	 *
	 * @param remainingScopes the scopes that should remain faceted after the operation
	 * @return this builder instance for fluent chaining
	 */
	@Nonnull
	protected T applyNonFacetedMutation(@Nonnull Scope[] remainingScopes) {
		final EnumSet<Scope> remainingScopeSet = ArrayUtils.toEnumSet(Scope.class, remainingScopes);
		final ScopedFacetedPartially[] filteredPartially =
			toScopedFacetedPartiallyArray(filterPartiallyToScopes(remainingScopeSet));
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(getName(), remainingScopes, filteredPartially)
			)
		);
		return (T) this;
	}

	/**
	 * Filters the current `facetedPartiallyInScopes` map to only retain entries for scopes
	 * present in the given set.
	 *
	 * @param retainedScopes scopes to keep
	 * @return filtered map containing only entries for retained scopes
	 */
	@Nonnull
	protected Map<Scope, Expression> filterPartiallyToScopes(
		@Nonnull EnumSet<Scope> retainedScopes
	) {
		final Map<Scope, Expression> current = toInstance().getFacetedPartiallyInScopes();
		if (current.isEmpty()) {
			return current;
		}
		final EnumMap<Scope, Expression> filtered = new EnumMap<>(Scope.class);
		for (final Map.Entry<Scope, Expression> entry : current.entrySet()) {
			if (retainedScopes.contains(entry.getKey())) {
				filtered.put(entry.getKey(), entry.getValue());
			}
		}
		return filtered;
	}

	/**
	 * Converts a map of per-scope expressions to the array form expected by
	 * {@link SetReferenceSchemaFacetedMutation}.
	 *
	 * @param partiallyMap per-scope expressions (may be empty)
	 * @return the corresponding array, possibly empty but never null
	 */
	@Nonnull
	protected static ScopedFacetedPartially[] toScopedFacetedPartiallyArray(
		@Nonnull Map<Scope, Expression> partiallyMap
	) {
		if (partiallyMap.isEmpty()) {
			return ScopedFacetedPartially.EMPTY;
		}
		final ScopedFacetedPartially[] result =
			new ScopedFacetedPartially[partiallyMap.size()];
		int i = 0;
		for (final Map.Entry<Scope, Expression> entry : partiallyMap.entrySet()) {
			result[i++] = new ScopedFacetedPartially(entry.getKey(), entry.getValue());
		}
		return result;
	}

	@Nonnull
	@Override
	public T indexedForFilteringInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = indexedForTypeInScope(
			this.catalogSchema, this.entitySchema, this.mutations,
			this.updatedSchemaDirty, getName(), ReferenceIndexType.FOR_FILTERING,
			getIndexedComponentsInScopes(), inScope
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T indexedForFilteringAndPartitioningInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = indexedForTypeInScope(
			this.catalogSchema, this.entitySchema, this.mutations,
			this.updatedSchemaDirty, getName(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING,
			getIndexedComponentsInScopes(), inScope
		);
		return (T) this;
	}

	@Override
	@Nonnull
	public T withoutAttribute(@Nonnull String attributeName) {
		checkSortableAttributeCompoundsWithoutAttribute(
			attributeName, this.getSortableAttributeCompounds().values()
		);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceAttributeSchemaMutation(
					this.getName(),
					new RemoveAttributeSchemaMutation(attributeName)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	) {
		return withSortableAttributeCompound(
			name, attributeElements, null
		);
	}

	@Nonnull
	@Override
	public T withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		this.updatedSchemaDirty = addSortableAttributeCompoundToReference(
			this.catalogSchema, this.entitySchema, this, this.baseSchema,
			this.mutations, this.updatedSchemaDirty,
			name, attributeElements, whichIs
		);
		return (T) this;
	}

	@Nonnull
	@Override
	public T withoutSortableAttributeCompound(@Nonnull String name) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSortableAttributeCompoundSchemaMutation(
					this.getName(),
					new RemoveSortableAttributeCompoundSchemaMutation(name)
				)
			)
		);
		return (T) this;
	}

	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		// apply necessary mutation sort
		sortReferenceAttributeMutationsLast(this.mutations);
		// and return mutations
		return this.mutations;
	}

	/**
	 * Creates an {@link AttributeSchemaBuilder} from an existing or new attribute definition,
	 * applies optional consumer-based configuration, and validates sortable traits.
	 * This method captures the logic shared by both {@link ReferenceSchemaBuilder} and
	 * {@link ReflectedReferenceSchemaBuilder} in their `withAttribute` methods.
	 *
	 * @param attributeName     name of the attribute being defined
	 * @param ofType            the expected type of the attribute
	 * @param existingAttribute the previously existing attribute, if any
	 * @param whichIs           optional consumer to further configure the attribute
	 * @return the build result containing both the builder and the built schema
	 */
	@Nonnull
	protected AttributeSchemaBuildResult buildAttributeSchema(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable AttributeSchemaContract existingAttribute,
		@Nullable Consumer<AttributeSchemaEditor.AttributeSchemaBuilder> whichIs
	) {
		final AttributeSchemaBuilder attributeSchemaBuilder;
		if (existingAttribute == null) {
			attributeSchemaBuilder = new AttributeSchemaBuilder(this.entitySchema, attributeName, ofType);
		} else {
			Assert.isTrue(
				ofType.equals(existingAttribute.getType()),
				() -> new InvalidSchemaMutationException(
					"Attribute " + attributeName + " has already assigned type " +
						existingAttribute.getType() + ", cannot change this type to: " + ofType + "!"
				)
			);
			attributeSchemaBuilder = new AttributeSchemaBuilder(this.entitySchema, existingAttribute);
		}

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final AttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();
		checkSortableTraits(attributeName, attributeSchema);
		return new AttributeSchemaBuildResult(attributeSchemaBuilder, attributeSchema);
	}

	/**
	 * Adds attribute mutations to the mutation list when the attribute has actually changed
	 * compared to its previous version (or is entirely new).
	 *
	 * @param existingAttribute      the previously existing attribute, if any
	 * @param attributeSchema        the new attribute schema instance
	 * @param attributeSchemaBuilder the builder that produced the attribute schema
	 */
	protected void addAttributeMutationsIfChanged(
		@Nullable AttributeSchemaContract existingAttribute,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull AttributeSchemaBuilder attributeSchemaBuilder
	) {
		if (existingAttribute == null || !existingAttribute.equals(attributeSchema)) {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					attributeSchemaBuilder
						.toReferenceMutation(getName())
						.stream()
						.map(LocalEntitySchemaMutation.class::cast)
						.toArray(LocalEntitySchemaMutation[]::new)
				)
			);
		}
	}

	/**
	 * Applies all accumulated mutations to the base schema and returns the current schema instance.
	 * Uses lazy computation with dirty-flag optimization to avoid unnecessary recomputation.
	 * Subclasses must call this from their `@Delegate`-annotated `toInstanceInternal()` method.
	 *
	 * @return the current schema instance reflecting all applied mutations
	 */
	@Nonnull
	protected S toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			S currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			if (this.lastMutationReflectedInSchema < this.mutations.size()) {
				// apply the mutations not reflected in the schema
				for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
					final LocalEntitySchemaMutation mutation = this.mutations.get(i);
					currentSchema = mutateSchema(currentSchema, mutation);
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
		}
		return this.updatedSchema;
	}

	/**
	 * Applies a single mutation to the current schema during incremental schema building.
	 * Subclasses implement this to provide the correct cast, consistency check behavior,
	 * and appropriate error message when the mutation unexpectedly removes the schema.
	 *
	 * @param currentSchema the current schema state
	 * @param mutation      the mutation to apply
	 * @return the mutated schema, never null
	 */
	@Nonnull
	protected abstract S mutateSchema(@Nonnull S currentSchema, @Nonnull LocalEntitySchemaMutation mutation);

	/**
	 * Intermediate result of building an attribute schema — holds the builder (needed for
	 * mutation generation) together with the built immutable schema instance.
	 *
	 * @param builder the attribute schema builder
	 * @param schema  the built attribute schema instance
	 */
	protected record AttributeSchemaBuildResult(
		@Nonnull AttributeSchemaBuilder builder,
		@Nonnull AttributeSchemaContract schema
	) {
	}

	/**
	 * The result of building a reference schema. Contains the built reference schema
	 * and a collection of mutations applied to the schema.
	 */
	public record ReferenceSchemaBuilderResult(
		@Nonnull ReferenceSchemaContract schema,
		@Nonnull Collection<LocalEntitySchemaMutation> mutations
	) {

	}

}
