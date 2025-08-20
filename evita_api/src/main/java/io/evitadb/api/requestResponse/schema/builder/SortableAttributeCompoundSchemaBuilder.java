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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.SetSortableAttributeCompoundIndexedMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Internal {@link SortableAttributeCompoundSchema} builder used solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class SortableAttributeCompoundSchemaBuilder
	implements SortableAttributeCompoundSchemaEditor.SortableAttributeCompoundSchemaBuilder, InternalSchemaBuilderHelper {
	@Serial private static final long serialVersionUID = -6435272035844056999L;

	private final CatalogSchemaContract catalogSchema;
	private final EntitySchemaContract entitySchema;
	private final ReferenceSchemaContract referenceSchema;
	private final SortableAttributeCompoundSchemaContract baseSchema;
	private final List<LocalEntitySchemaMutation> mutations = new LinkedList<>();
	private MutationImpact updatedSchemaDirty = MutationImpact.NO_IMPACT;
	private int lastMutationReflectedInSchema = 0;
	private SortableAttributeCompoundSchemaContract updatedSchema;

	SortableAttributeCompoundSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract existingSchema,
		@Nonnull String name,
		@Nonnull List<AttributeElement> attributeElements,
		@Nonnull List<LocalEntitySchemaMutation> mutations,
		boolean createNew
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.baseSchema = existingSchema == null ?
			SortableAttributeCompoundSchema._internalBuild(
				name, null, null,
				// by default sortable attribute compound is indexed in LIVE scope
				Scope.DEFAULT_SCOPES,
				attributeElements
			) :
			existingSchema;
		if (createNew) {
			this.mutations.add(
				new CreateSortableAttributeCompoundSchemaMutation(
					this.baseSchema.getName(),
					this.baseSchema.getDescription(),
					this.baseSchema.getDeprecationNotice(),
					Arrays.stream(Scope.values())
						.filter(this.baseSchema::isIndexedInScope)
						.toArray(Scope[]::new),
					attributeElements.toArray(AttributeElement[]::new)
				)
			);
		}
		mutations.stream()
			.filter(it -> it instanceof ReferenceSchemaMutation referenceSchemaMutation &&
				(name.equals(referenceSchemaMutation.getName()) && !(referenceSchemaMutation instanceof CreateReferenceSchemaMutation)))
			.forEach(this.mutations::add);
	}

	@Override
	@Nonnull
	public SortableAttributeCompoundSchemaBuilder withDescription(@Nullable String description) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifySortableAttributeCompoundSchemaDescriptionMutation(getName(), description)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public SortableAttributeCompoundSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public SortableAttributeCompoundSchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(getName(), null)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public SortableAttributeCompoundSchemaBuilder indexedInScope(@Nullable Scope... inScope) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetSortableAttributeCompoundIndexedMutation(getName(), inScope)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public SortableAttributeCompoundSchemaBuilder nonIndexed(@Nullable Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				this.catalogSchema, this.entitySchema, this.mutations,
				new SetSortableAttributeCompoundIndexedMutation(
					getName(),
					Arrays.stream(Scope.values())
						.filter(this::isIndexedInScope)
						.filter(it -> !excludedScopes.contains(it))
						.toArray(Scope[]::new)
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public Collection<LocalEntitySchemaMutation> toMutation() {
		return this.mutations;
	}

	@Nonnull
	@Override
	public Collection<SortableAttributeCompoundSchemaMutation> toSortableAttributeCompoundSchemaMutation() {
		return this.mutations
			.stream()
			.map(SortableAttributeCompoundSchemaMutation.class::cast)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName) {
		return this.mutations
			.stream()
			.map(it -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(referenceName, (ReferenceSortableAttributeCompoundSchemaMutation) it))
			.collect(Collectors.toList());
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = SortableAttributeCompoundSchemaContract.class)
	@Nonnull
	public SortableAttributeCompoundSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty != MutationImpact.NO_IMPACT) {
			// if the dirty flat is set to modified previous we need to start from the base schema again
			// and reapply all mutations
			if (this.updatedSchemaDirty == MutationImpact.MODIFIED_PREVIOUS) {
				this.lastMutationReflectedInSchema = 0;
			}
			// if the last mutation reflected in the schema is zero we need to start from the base schema
			// else we can continue modification last known updated schema by adding additional mutations
			SortableAttributeCompoundSchemaContract currentSchema = this.lastMutationReflectedInSchema == 0 ?
				this.baseSchema : this.updatedSchema;

			// apply the mutations not reflected in the schema
			for (int i = this.lastMutationReflectedInSchema; i < this.mutations.size(); i++) {
				final EntitySchemaMutation mutation = this.mutations.get(i);
				currentSchema = ((SortableAttributeCompoundSchemaMutation) mutation).mutate(this.entitySchema, this.referenceSchema, currentSchema);
				if (currentSchema == null) {
					throw new GenericEvitaInternalError("Attribute unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = MutationImpact.NO_IMPACT;
			this.lastMutationReflectedInSchema = this.mutations.size();
		}
		return this.updatedSchema;
	}

}
