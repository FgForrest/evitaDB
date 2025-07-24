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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.exception.AttributeAlreadyPresentInEntitySchemaException;
import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.exception.SortableAttributeCompoundSchemaException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaContract.AttributeInheritanceBehavior;
import io.evitadb.api.requestResponse.schema.ReflectedReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReflectedReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator.ConsistencyChecks;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.*;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.RemoveSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.Scope;
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
import java.util.Collections;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static io.evitadb.utils.Assert.isTrue;
import static java.util.Optional.ofNullable;

/**
 * Internal {@link ReflectedReferenceSchema} builder used solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class ReflectedReferenceSchemaBuilder
	implements ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -6435272035844056999L;

	private final CatalogSchemaContract catalogSchema;
	private final EntitySchemaContract entitySchema;
	private final ReflectedReferenceSchema baseSchema;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private ReflectedReferenceSchema updatedSchema;

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
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema == null ?
			ReflectedReferenceSchema._internalBuild(name, entityType, reflectedReferenceName) :
			(ReflectedReferenceSchema) existingSchema;
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
	 * @param description new value of description
	 * @return this
	 */
	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDescriptionMutation(getName(), description)
			)
		);
		return this;
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
	 * Note: setting null to the deprecation notice will cause the deprecation status is inherited from the original
	 * reference. The deprecation notice cannot be reset to not deprecated on the reflected reference if the original
	 * one is deprecated.
	 *
	 * @param deprecationNotice new value of deprecation notice
	 * @return this
	 */
	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
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
		final boolean reflectedReferenceAvailable = isReflectedReferenceAvailable();
		final boolean indexedInherited = isIndexedInherited();
		final boolean allInformationPresent = reflectedReferenceAvailable || !indexedInherited;
		if (allInformationPresent && Arrays.stream(inScope).allMatch(this::isIndexedInScope)) {
			// just update the faceted scopes
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaFacetedMutation(getName(), inScope)
				)
			);
		} else {
			// update both indexed and faceted scopes
			final EnumSet<Scope> includedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					new SetReferenceSchemaIndexedMutation(
						getName(),
						Arrays.stream(Scope.values())
							.filter(scope -> includedScopes.contains(scope) || (allInformationPresent && this.isIndexedInScope(scope)))
							.toArray(Scope[]::new)
					),
					new SetReferenceSchemaFacetedMutation(getName(), inScope)
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
					new SetReferenceSchemaFacetedMutation(getName(), Scope.NO_SCOPE)
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
			() -> new InvalidSchemaMutationException("Cannot update non-indexed scopes on inherited reference schema when the referenced schema is not available!")
		);

		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		final Scope[] newScopes = getFacetedInScopes().stream()
			.filter(it -> !excludedScopes.contains(it))
			.toArray(Scope[]::new);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(getName(), newScopes)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder indexedForFilteringInScope(@Nonnull Scope... inScope) {
		final ScopedReferenceIndexType[] scopedIndexTypes = Arrays.stream(inScope)
			.map(scope -> new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING))
			.toArray(ScopedReferenceIndexType[]::new);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(getName(), scopedIndexTypes)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder indexedForFilteringAndPartitioningInScope(@Nonnull Scope... inScope) {
		final ScopedReferenceIndexType[] scopedIndexTypes = Arrays.stream(inScope)
			.map(scope -> new ScopedReferenceIndexType(scope, ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING))
			.toArray(ScopedReferenceIndexType[]::new);

		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(getName(), scopedIndexTypes)
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
		final Optional<AttributeSchemaContract> existingAttribute = instance.getDeclaredAttribute(attributeName);
		final AttributeSchemaBuilder attributeSchemaBuilder =
			existingAttribute
				.map(it -> {
					Assert.isTrue(
						ofType.equals(it.getType()),
						() -> new InvalidSchemaMutationException(
							"Attribute " + attributeName + " has already assigned type " + it.getType() +
								", cannot change this type to: " + ofType + "!"
						)
					);
					return new AttributeSchemaBuilder(this.entitySchema, it);
				})
				.orElseGet(() -> new AttributeSchemaBuilder(this.entitySchema, attributeName, ofType));

		ofNullable(whichIs).ifPresent(it -> it.accept(attributeSchemaBuilder));
		final AttributeSchemaContract attributeSchema = attributeSchemaBuilder.toInstance();
		checkSortableTraits(attributeName, attributeSchema);

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			instance.isReflectedReferenceAvailable() ?
				instance.getAttributes().values() :
				instance.getDeclaredAttributes().values(),
			instance.isReflectedReferenceAvailable() ?
				instance.getSortableAttributeCompounds().values() :
				instance.getDeclaredSortableAttributeCompounds().values(),
			attributeSchema
		);

		if (existingAttribute.map(it -> !it.equals(attributeSchema)).orElse(true)) {
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
		return this;
	}

	@Override
	@Nonnull
	public ReflectedReferenceSchemaBuilder withoutAttribute(@Nonnull String attributeName) {
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
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	) {
		return withSortableAttributeCompound(
			name, attributeElements, null
		);
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		final Optional<SortableAttributeCompoundSchemaContract> existingCompound = getSortableAttributeCompound(name);
		final SortableAttributeCompoundSchemaBuilder builder = new SortableAttributeCompoundSchemaBuilder(
			this.catalogSchema,
			this.entitySchema,
			this,
			this.baseSchema.getSortableAttributeCompound(name).orElse(null),
			name,
			Arrays.asList(attributeElements),
			Collections.emptyList(),
			true
		);
		final SortableAttributeCompoundSchemaBuilder schemaBuilder =
			existingCompound
				.map(it -> {
					Assert.isTrue(
						it.getAttributeElements().equals(Arrays.asList(attributeElements)),
						() -> new AttributeAlreadyPresentInEntitySchemaException(
							it, builder.toInstance(), null, name
						)
					);
					return builder;
				})
				.orElse(builder);

		ofNullable(whichIs).ifPresent(it -> it.accept(schemaBuilder));
		final SortableAttributeCompoundSchemaContract compoundSchema = schemaBuilder.toInstance();
		isTrue(
			compoundSchema.getAttributeElements().size() > 1,
			() -> new SortableAttributeCompoundSchemaException(
				"Sortable attribute compound requires more than one attribute element!",
				compoundSchema
			)
		);
		isTrue(
			compoundSchema.getAttributeElements().size() ==
				compoundSchema.getAttributeElements()
					.stream()
					.map(AttributeElement::attributeName)
					.distinct()
					.count(),
			() -> new SortableAttributeCompoundSchemaException(
				"Attribute names of elements in sortable attribute compound must be unique!",
				compoundSchema
			)
		);
		checkSortableTraits(name, compoundSchema, this.getAttributes());

		// check the names in all naming conventions are unique in the catalog schema
		checkNamesAreUniqueInAllNamingConventions(
			this.getAttributes().values(),
			this.getSortableAttributeCompounds().values(),
			compoundSchema
		);

		if (existingCompound.map(it -> !it.equals(compoundSchema)).orElse(true)) {
			this.updatedSchemaDirty = updateMutationImpact(
				this.updatedSchemaDirty,
				addMutations(
					this.catalogSchema, this.entitySchema, this.mutations,
					schemaBuilder
						.toReferenceMutation(getName())
						.stream()
						.map(LocalEntitySchemaMutation.class::cast)
						.toArray(LocalEntitySchemaMutation[]::new)
				)
			);
		}
		return this;
	}

	@Nonnull
	@Override
	public ReflectedReferenceSchemaBuilder withoutSortableAttributeCompound(@Nonnull String name) {
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
			currentSchema = (ReflectedReferenceSchema) ((ReferenceSchemaMutation) mutation).mutate(
				this.entitySchema, currentSchema,
				currentSchema.isReflectedReferenceAvailable() ? ConsistencyChecks.APPLY : ConsistencyChecks.SKIP
			);
		}
		return new ReferenceSchemaBuilderResult(currentSchema, mutations);
	}

	@Override
	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		// apply necessary mutation sort
		sortMutations();
		return this.mutations;
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = ReflectedReferenceSchema.class)
	private ReflectedReferenceSchema toInstanceInternal() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flat is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			ReflectedReferenceSchema currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			if (this.lastMutationReflectedInSchema < this.mutations.size()) {
				// apply the mutations not reflected in the schema
				for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
					final LocalEntitySchemaMutation mutation = this.mutations.get(i);
					currentSchema = (ReflectedReferenceSchema) ((ReferenceSchemaMutation) mutation).mutate(this.entitySchema, currentSchema, ReferenceSchemaMutator.ConsistencyChecks.SKIP);
					if (currentSchema == null) {
						throw new GenericEvitaInternalError("Reflected reference unexpectedly removed from inside!");
					}
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
		}
		return this.updatedSchema;
	}

	/**
	 * Sorts mutations, so that attribute mutations always come last.
	 */
	private void sortMutations() {
		// sort the mutations first, we need to apply reference schema properties such as faceted / indexed first
		final Iterator<LocalEntitySchemaMutation> it = this.mutations.iterator();
		final List<LocalEntitySchemaMutation> movedMutations = new LinkedList<>();
		while (it.hasNext()) {
			final LocalEntitySchemaMutation mutation = it.next();
			if (mutation instanceof ModifyReferenceAttributeSchemaMutation) {
				it.remove();
				movedMutations.add(mutation);
			}
		}
		this.mutations.addAll(movedMutations);
	}

	/**
	 * The {@code ReferenceSchemaBuilderResult} class represents the result of building a reference schema.
	 * It contains the built reference schema and a collection of mutations applied to the schema.
	 */
	public record ReferenceSchemaBuilderResult(
		@Nonnull ReferenceSchemaContract schema,
		@Nonnull Collection<LocalEntitySchemaMutation> mutations
	) {

	}

}
