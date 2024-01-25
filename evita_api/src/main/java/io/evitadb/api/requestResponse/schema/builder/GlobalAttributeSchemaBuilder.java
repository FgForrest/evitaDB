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
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaRepresentativeMutation;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Internal {@link io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder} builder used
 * solely from within {@link InternalEntitySchemaBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public final class GlobalAttributeSchemaBuilder
	extends AbstractAttributeSchemaBuilder<GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder, GlobalAttributeSchemaContract>
	implements GlobalAttributeSchemaEditor.GlobalAttributeSchemaBuilder {
	@Serial private static final long serialVersionUID = -119291228918162813L;

	private final CatalogSchemaContract catalogSchema;
	private final List<LocalCatalogSchemaMutation> mutations = new LinkedList<>();

	GlobalAttributeSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull GlobalAttributeSchemaContract existingSchema
	) {
		super(catalogSchema, null, existingSchema);
		this.catalogSchema = catalogSchema;
	}

	GlobalAttributeSchemaBuilder(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> ofType
	) {
		super(catalogSchema, null, GlobalAttributeSchema._internalBuild(name, ofType, false));
		this.catalogSchema = catalogSchema;
		this.mutations.add(
			new CreateGlobalAttributeSchemaMutation(
				baseSchema.getName(),
				baseSchema.getDescription(),
				baseSchema.getDeprecationNotice(),
				baseSchema.getUniquenessType(),
				baseSchema.getGlobalUniquenessType(),
				baseSchema.isFilterable(),
				baseSchema.isSortable(),
				baseSchema.isLocalized(),
				baseSchema.isNullable(),
				baseSchema.isRepresentative(),
				baseSchema.getType(),
				baseSchema.getDefaultValue(),
				baseSchema.getIndexedDecimalPlaces()
			)
		);
	}

	@Override
	protected Class<GlobalAttributeSchemaContract> getAttributeSchemaType() {
		return GlobalAttributeSchemaContract.class;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGlobally() {
		this.updatedSchemaDirty = addMutations(
			new SetAttributeSchemaGloballyUniqueMutation(
				toInstance().getName(),
				GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGloballyWithinLocale() {
		this.updatedSchemaDirty = addMutations(
			new SetAttributeSchemaGloballyUniqueMutation(
				toInstance().getName(),
				GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGlobally(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaGloballyUniqueMutation(
					toInstance().getName(),
					decider.getAsBoolean() ?
						GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG : GlobalAttributeUniquenessType.NOT_UNIQUE
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGloballyWithinLocale(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaGloballyUniqueMutation(
					toInstance().getName(),
					decider.getAsBoolean() ?
						GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE : GlobalAttributeUniquenessType.NOT_UNIQUE
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder representative() {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					baseSchema.getName(),
					true
				)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public GlobalAttributeSchemaBuilder representative(@Nonnull BooleanSupplier decider) {
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaRepresentativeMutation(
					baseSchema.getName(),
					decider.getAsBoolean()
				)
			)
		);
		return this;
	}

	@Nonnull
	public Collection<LocalCatalogSchemaMutation> toMutation() {
		return this.mutations;
	}

	@Delegate(types = GlobalAttributeSchemaContract.class)
	@Nonnull
	@Override
	public GlobalAttributeSchemaContract toInstance() {
		return super.toInstance();
	}

	@Override
	protected MutationImpact addMutations(@Nonnull AttributeSchemaMutation mutation) {
		return addMutations(
			this.catalogSchema,
			this.mutations,
			(LocalCatalogSchemaMutation) mutation
		);
	}

	@Nonnull
	@Override
	protected List<AttributeSchemaMutation> toAttributeMutation() {
		// faster version of the:
		/* return this.mutations
			.stream()
			.map(it -> (AttributeSchemaMutation) it)
			.collect(Collectors.toList());
			*/
		//noinspection unchecked,rawtypes
		return (List) this.mutations;
	}
}
