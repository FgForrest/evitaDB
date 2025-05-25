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
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaEditor;
import io.evitadb.api.requestResponse.schema.dto.AttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.CreateGlobalAttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaGloballyUniqueMutation;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaRepresentativeMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
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
				this.baseSchema.getName(),
				this.baseSchema.getDescription(),
				this.baseSchema.getDeprecationNotice(),
				Arrays.stream(Scope.values())
					.map(scope -> new ScopedAttributeUniquenessType(scope, this.baseSchema.getUniquenessType(scope)))
					// filter out default values
					.filter(it -> it.uniquenessType() != AttributeUniquenessType.NOT_UNIQUE)
					.toArray(ScopedAttributeUniquenessType[]::new),
				Arrays.stream(Scope.values())
					.map(scope -> new ScopedGlobalAttributeUniquenessType(scope, this.baseSchema.getGlobalUniquenessType(scope)))
					// filter out default values
					.filter(it -> it.uniquenessType() != GlobalAttributeUniquenessType.NOT_UNIQUE)
					.toArray(ScopedGlobalAttributeUniquenessType[]::new),
				Arrays.stream(Scope.values()).filter(this.baseSchema::isFilterableInScope).toArray(Scope[]::new),
				Arrays.stream(Scope.values()).filter(this.baseSchema::isSortableInScope).toArray(Scope[]::new),
				this.baseSchema.isLocalized(),
				this.baseSchema.isNullable(),
				this.baseSchema.isRepresentative(),
				this.baseSchema.getType(),
				this.baseSchema.getDefaultValue(),
				this.baseSchema.getIndexedDecimalPlaces()
			)
		);
	}

	@Override
	protected Class<GlobalAttributeSchemaContract> getAttributeSchemaType() {
		return GlobalAttributeSchemaContract.class;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGloballyInScope(@Nonnull Scope... scope) {
		this.updatedSchemaDirty = addMutations(
			new SetAttributeSchemaGloballyUniqueMutation(
				toInstance().getName(),
				Arrays.stream(scope)
					.map(it -> new ScopedGlobalAttributeUniquenessType(it, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG))
					.toArray(ScopedGlobalAttributeUniquenessType[]::new)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public GlobalAttributeSchemaBuilder nonUniqueGloballyInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaGloballyUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> !this.isUniqueWithinLocaleInScope(it) || !excludedScopes.contains(it))
						.map(it -> new ScopedGlobalAttributeUniquenessType(it, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG))
						.toArray(ScopedGlobalAttributeUniquenessType[]::new)
				)
			)
		);
		return this;
	}

	@Override
	@Nonnull
	public GlobalAttributeSchemaBuilder uniqueGloballyWithinLocaleInScope(@Nonnull Scope... scope) {
		this.updatedSchemaDirty = addMutations(
			new SetAttributeSchemaGloballyUniqueMutation(
				toInstance().getName(),
				Arrays.stream(scope)
					.map(it -> new ScopedGlobalAttributeUniquenessType(it, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE))
					.toArray(ScopedGlobalAttributeUniquenessType[]::new)
			)
		);
		return this;
	}

	@Nonnull
	@Override
	public GlobalAttributeSchemaBuilder nonUniqueGloballyWithinLocaleInScope(@Nonnull Scope... inScope) {
		final EnumSet<Scope> excludedScopes = ArrayUtils.toEnumSet(Scope.class, inScope);
		this.updatedSchemaDirty = updateMutationImpact(
			this.updatedSchemaDirty,
			addMutations(
				new SetAttributeSchemaGloballyUniqueMutation(
					this.baseSchema.getName(),
					Arrays.stream(Scope.values())
						.filter(it -> !this.isUniqueWithinLocaleInScope(it) || !excludedScopes.contains(it))
						.map(it -> new ScopedGlobalAttributeUniquenessType(it, GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE))
						.toArray(ScopedGlobalAttributeUniquenessType[]::new)
				)
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
					new ScopedGlobalAttributeUniquenessType[] {
						new ScopedGlobalAttributeUniquenessType(
							Scope.DEFAULT_SCOPE,
							decider.getAsBoolean() ?
								GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG : GlobalAttributeUniquenessType.NOT_UNIQUE
						)
					}
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
					this.baseSchema.getName(),
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
					this.baseSchema.getName(),
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
