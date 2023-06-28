/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.CreateReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.reference.ModifyReferenceSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation;
import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.ModifySortableAttributeCompoundSchemaDescriptionMutation;
import io.evitadb.exception.EvitaInternalError;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.util.Collection;
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
	private final List<EntitySchemaMutation> mutations = new LinkedList<>();
	private boolean updatedSchemaDirty;
	private SortableAttributeCompoundSchemaContract updatedSchema;

	SortableAttributeCompoundSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaContract entitySchema,
		@Nullable ReferenceSchemaContract referenceSchema,
		@Nullable SortableAttributeCompoundSchemaContract existingSchema,
		@Nonnull String name,
		@Nonnull List<AttributeElement> attributeElements,
		@Nonnull List<EntitySchemaMutation> mutations,
		boolean createNew
	) {
		this.catalogSchema = catalogSchema;
		this.entitySchema = entitySchema;
		this.referenceSchema = referenceSchema;
		this.baseSchema = existingSchema == null ?
			SortableAttributeCompoundSchema._internalBuild(
				name, null, null, attributeElements
			) :
			existingSchema;
		if (createNew) {
			this.mutations.add(
				new CreateSortableAttributeCompoundSchemaMutation(
					baseSchema.getName(),
					baseSchema.getDescription(),
					baseSchema.getDeprecationNotice(),
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
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifySortableAttributeCompoundSchemaDescriptionMutation(getName(), description)
		);
		return this;
	}

	@Override
	@Nonnull
	public SortableAttributeCompoundSchemaBuilder deprecated(@Nonnull String deprecationNotice) {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(getName(), deprecationNotice)
		);
		return this;
	}

	@Override
	@Nonnull
	public SortableAttributeCompoundSchemaBuilder notDeprecatedAnymore() {
		this.updatedSchemaDirty = addMutations(
			this.catalogSchema, this.entitySchema, this.mutations,
			new ModifySortableAttributeCompoundSchemaDeprecationNoticeMutation(getName(), null)
		);
		return this;
	}

	@Override
	@Nonnull
	public Collection<EntitySchemaMutation> toMutation() {
		return this.mutations;
	}

	@Nonnull
	@Override
	public Collection<SortableAttributeCompoundSchemaMutation> toSortableAttributeCompoundSchemaMutation() {
		return this.mutations
			.stream()
			.map(it -> (SortableAttributeCompoundSchemaMutation) it)
			.collect(Collectors.toList());
	}

	@Nonnull
	@Override
	public Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName) {
		return this.mutations
			.stream()
			.map(it -> new ModifyReferenceSortableAttributeCompoundSchemaMutation(referenceName, (ReferenceSchemaMutation) it))
			.collect(Collectors.toList());
	}

	/**
	 * Builds new instance of immutable {@link ReferenceSchemaContract} filled with updated configuration.
	 */
	@Delegate(types = SortableAttributeCompoundSchemaContract.class)
	@Nonnull
	public SortableAttributeCompoundSchemaContract toInstance() {
		if (this.updatedSchema == null || this.updatedSchemaDirty) {
			SortableAttributeCompoundSchemaContract currentSchema = this.baseSchema;
			for (EntitySchemaMutation mutation : this.mutations) {
				currentSchema = ((SortableAttributeCompoundSchemaMutation)mutation).mutate(entitySchema, referenceSchema, currentSchema);
				if (currentSchema == null) {
					throw new EvitaInternalError("Attribute unexpectedly removed from inside!");
				}
			}
			this.updatedSchema = currentSchema;
			this.updatedSchemaDirty = false;
		}
		return this.updatedSchema;
	}

}
