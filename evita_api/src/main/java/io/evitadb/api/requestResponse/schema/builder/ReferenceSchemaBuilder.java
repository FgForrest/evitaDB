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

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement;
import io.evitadb.api.requestResponse.schema.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutator;
import io.evitadb.api.requestResponse.schema.mutation.attribute.RemoveAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaCardinalityMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityGroupMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSchemaRelatedEntityMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.RemoveReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaFacetedMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.SetReferenceSchemaIndexedMutation;
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
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

import static java.util.Optional.ofNullable;

/**
 * Internal {@link ReferenceSchema} builder used solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class ReferenceSchemaBuilder
	implements ReferenceSchemaEditor.ReferenceSchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -6435272035844056999L;

	private final CatalogSchemaContract catalogSchema;
	private final EntitySchemaContract entitySchema;
	private final ReferenceSchemaContract baseSchema;
	private final LinkedList<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private ReferenceSchemaContract updatedSchema;

	ReferenceSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract existingSchema,
		@Nonnull String name,
		@Nonnull String entityType,
		boolean referencedEntityTypeManaged,
		@Nonnull Cardinality cardinality,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		boolean createNew
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema == null ?
			ReferenceSchema._internalBuild(
				name, entityType, referencedEntityTypeManaged, cardinality,
				null, false,
				ScopedReferenceIndexType.EMPTY, Scope.NO_SCOPE
			) :
			existingSchema;
		if (createNew) {
			this.mutations.add(
				new CreateReferenceSchemaMutation(
					this.baseSchema.getName(),
					this.baseSchema.getDescription(),
					this.baseSchema.getDeprecationNotice(),
					cardinality,
					entityType,
					referencedEntityTypeManaged,
					this.baseSchema.getReferencedGroupType(),
					this.baseSchema.isReferencedGroupTypeManaged(),
					this.baseSchema.getReferenceIndexTypeInScopes()
						.entrySet()
						.stream()
						.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
						.toArray(ScopedReferenceIndexType[]::new),
					Arrays.stream(Scope.values()).filter(this.baseSchema::isFacetedInScope).toArray(Scope[]::new)
				)
			);
		} else {
			Assert.isPremiseValid(
				existingSchema != null,
				"When not creating new reference schema, the existing schema must be provided!"
			);
			if (referencedEntityTypeManaged != existingSchema.isReferencedEntityTypeManaged() || !entityType.equals(existingSchema.getReferencedEntityType())) {
				this.mutations.add(
					new ModifyReferenceSchemaRelatedEntityMutation(
						this.baseSchema.getName(),
						entityType,
						referencedEntityTypeManaged
					)
				);
			}
			if (cardinality != existingSchema.getCardinality()) {
				this.mutations.add(
					new ModifyReferenceSchemaCardinalityMutation(
						this.baseSchema.getName(),
						cardinality
					)
				);
			}
		}
		mutations.stream()
			.filter(
				it -> it instanceof ReferenceSchemaMutation referenceSchemaMutation &&
						(name.equals(referenceSchemaMutation.getName()) &&
						!(referenceSchemaMutation instanceof CreateReferenceSchemaMutation)) &&
						!(referenceSchemaMutation instanceof RemoveReferenceSchemaMutation)
			)
			.forEach(this.mutations::add);
	}

	public ReferenceSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nonnull ReferenceSchemaContract existingSchema
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.baseSchema = existingSchema;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDescriptionMutation(getName(), description)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder notDeprecatedAnymore() {
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
	public ReferenceSchemaBuilder withGroupType(@Nonnull String groupType) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), groupType, false)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withGroupTypeRelatedToEntity(@Nonnull String groupType) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), groupType, true)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withoutGroupType() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifyReferenceSchemaRelatedEntityGroupMutation(getName(), null, false)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder indexedInScope(@Nullable Scope... inScope) {
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
	public ReferenceSchemaBuilder nonIndexed(@Nullable Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaIndexedMutation(
					getName(),
					this.getReferenceIndexTypeInScopes()
						.entrySet()
						.stream()
						.filter(it -> !excludedScopes.contains(it.getKey()))
						.map(it -> new ScopedReferenceIndexType(it.getKey(), it.getValue()))
						.toArray(ScopedReferenceIndexType[]::new)
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder facetedInScope(@Nonnull Scope... inScope) {
		if (Arrays.stream(inScope).allMatch(this::isIndexedInScope)) {
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
							.filter(scope -> includedScopes.contains(scope) || this.isIndexedInScope(scope))
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
	public ReferenceSchemaBuilder nonFaceted(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetReferenceSchemaFacetedMutation(
					getName(),
					Arrays.stream(Scope.values())
						.filter(this::isFacetedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder indexedForFilteringInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = indexedForTypeInScope(
			this.catalogSchema, this.entitySchema, this.mutations,
			this.updatedSchemaDirty, getName(), ReferenceIndexType.FOR_FILTERING, inScope
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder indexedForFilteringAndPartitioningInScope(@Nonnull Scope... inScope) {
		this.updatedSchemaDirty = indexedForTypeInScope(
			this.catalogSchema, this.entitySchema, this.mutations,
			this.updatedSchemaDirty, getName(), ReferenceIndexType.FOR_FILTERING_AND_PARTITIONING, inScope
		);
		return this;
	}

	@Override
	@Nonnull
	public ReferenceSchemaBuilder withAttribute(@Nonnull String attributeName, @Nonnull Class<? extends Serializable> ofType) {
		return withAttribute(attributeName, ofType, null);
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withAttribute(
		@Nonnull String attributeName,
		@Nonnull Class<? extends Serializable> ofType,
		@Nullable Consumer<AttributeSchemaEditor.AttributeSchemaBuilder> whichIs
	) {
		final Optional<AttributeSchemaContract> existingAttribute = getAttribute(attributeName);
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
			this.getAttributes().values(),
			this.getSortableAttributeCompounds().values(),
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
	public ReferenceSchemaBuilder withoutAttribute(@Nonnull String attributeName) {
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
	public ReferenceSchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement... attributeElements
	) {
		return withSortableAttributeCompound(
			name, attributeElements, null
		);
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withSortableAttributeCompound(
		@Nonnull String name,
		@Nonnull AttributeElement[] attributeElements,
		@Nullable Consumer<SortableAttributeCompoundSchemaBuilder> whichIs
	) {
		this.updatedSchemaDirty = addSortableAttributeCompoundToReference(
			this.catalogSchema, this.entitySchema, this, this.baseSchema,
			this.mutations, this.updatedSchemaDirty,
			name, attributeElements, whichIs
		);
		return this;
	}

	@Nonnull
	@Override
	public ReferenceSchemaBuilder withoutSortableAttributeCompound(@Nonnull String name) {
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
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration and validates
	 * consistency of the result.
	 */
	@Nonnull
	public ReferenceSchemaBuilderResult toResult() {
		final Collection<LocalEntitySchemaMutation> mutations = toMutation();
		// and now rebuild the schema from scratch including consistency checks
		ReferenceSchemaContract currentSchema = this.baseSchema;
		for (LocalEntitySchemaMutation mutation : mutations) {
			currentSchema = Objects.requireNonNull(
				((ReferenceSchemaMutation) mutation).mutate(this.entitySchema, currentSchema)
			);
		}
		return new ReferenceSchemaBuilderResult(currentSchema, mutations);
	}

	@Override
	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		// apply necessary mutation sort
		sortReferenceAttributeMutationsLast(this.mutations);
		// and return mutations
		return this.mutations;
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = ReferenceSchemaContract.class)
	private ReferenceSchemaContract toInstanceInternal() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flag is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			ReferenceSchemaContract currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			if (this.lastMutationReflectedInSchema < this.mutations.size()) {
				// apply the mutations not reflected in the schema
				for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
					final LocalEntitySchemaMutation mutation = this.mutations.get(i);
					currentSchema = ((ReferenceSchemaMutation) mutation).mutate(this.entitySchema, currentSchema, ReferenceSchemaMutator.ConsistencyChecks.SKIP);
					if (currentSchema == null) {
						throw new GenericEvitaInternalError("Reference unexpectedly removed from inside!");
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
	 * The {@code ReferenceSchemaBuilderResult} class represents the result of building a reference schema.
	 * It contains the built reference schema and a collection of mutations applied to the schema.
	 */
	public record ReferenceSchemaBuilderResult(
		@Nonnull ReferenceSchemaContract schema,
		@Nonnull Collection<LocalEntitySchemaMutation> mutations
	) {

	}

}
