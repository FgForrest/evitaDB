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

package io.evitadb.api.requestResponse.schema.mutation.attribute;

import io.evitadb.api.exception.InvalidSchemaMutationException;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.annotation.SerializableCreator;
import io.evitadb.api.requestResponse.schema.builder.InternalSchemaBuilderHelper.MutationCombinationResult;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.GlobalAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.CombinableCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.CombinableLocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.dataType.Scope;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.Assert;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Arrays;
import java.util.EnumMap;

import static io.evitadb.api.requestResponse.schema.dto.GlobalAttributeSchema.toGlobalUniquenessEnumMap;

/**
 * Mutation is responsible for setting value to a {@link GlobalAttributeSchemaContract#isUniqueGlobally()}
 * in {@link EntitySchemaContract}.
 * Mutation can be used for altering also the existing {@link GlobalAttributeSchemaContract} alone.
 * Mutation implements {@link CombinableLocalEntitySchemaMutation} allowing to resolve conflicts with the same mutation
 * if the mutation is placed twice in the mutation pipeline.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class SetAttributeSchemaGloballyUniqueMutation
	implements GlobalAttributeSchemaMutation, CombinableCatalogSchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = 6770930613525155912L;
	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes;

	public SetAttributeSchemaGloballyUniqueMutation(@Nonnull String name, @Nonnull GlobalAttributeUniquenessType unique) {
		this(
			name,
			new ScopedGlobalAttributeUniquenessType[]{new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, unique)}
		);
	}

	@SerializableCreator
	public SetAttributeSchemaGloballyUniqueMutation(@Nonnull String name, @Nullable ScopedGlobalAttributeUniquenessType[] uniqueGloballyInScopes) {
		this.name = name;
		this.uniqueGloballyInScopes = uniqueGloballyInScopes == null ?
			new ScopedGlobalAttributeUniquenessType[]{
				new ScopedGlobalAttributeUniquenessType(Scope.DEFAULT_SCOPE, GlobalAttributeUniquenessType.NOT_UNIQUE)
			} : uniqueGloballyInScopes;
	}

	@Nonnull
	public GlobalAttributeUniquenessType getUniqueGlobally() {
		return Arrays.stream(this.uniqueGloballyInScopes)
			.filter(scope -> scope.scope() == Scope.DEFAULT_SCOPE)
			.map(ScopedGlobalAttributeUniquenessType::uniquenessType)
			.findFirst()
			.orElse(GlobalAttributeUniquenessType.NOT_UNIQUE);
	}

	@Nullable
	@Override
	public MutationCombinationResult<LocalCatalogSchemaMutation> combineWith(@Nonnull CatalogSchemaContract currentCatalogSchema, @Nonnull LocalCatalogSchemaMutation existingMutation) {
		if (existingMutation instanceof SetAttributeSchemaGloballyUniqueMutation theExistingMutation && this.name.equals(theExistingMutation.getName())) {
			return new MutationCombinationResult<>(null, this);
		} else {
			return null;
		}
	}

	@Nonnull
	@Override
	public <S extends AttributeSchemaContract> S mutate(@Nullable CatalogSchemaContract catalogSchema, @Nullable S attributeSchema, @Nonnull Class<S> schemaType) {
		Assert.isPremiseValid(attributeSchema != null, "Attribute schema is mandatory!");
		if (attributeSchema instanceof GlobalAttributeSchemaContract globalAttributeSchema) {
			final EnumMap<Scope, GlobalAttributeUniquenessType> uniqueGlobally = toGlobalUniquenessEnumMap(this.uniqueGloballyInScopes);
			if (uniqueGlobally.equals(globalAttributeSchema.getGlobalUniquenessTypeInScopes())) {
				//noinspection unchecked
				return (S) globalAttributeSchema;
			} else {
				//noinspection unchecked,rawtypes
				return (S) GlobalAttributeSchema._internalBuild(
					this.name,
					globalAttributeSchema.getNameVariants(),
					globalAttributeSchema.getDescription(),
					globalAttributeSchema.getDeprecationNotice(),
					globalAttributeSchema.getUniquenessTypeInScopes(),
					uniqueGlobally,
					globalAttributeSchema.getFilterableInScopes(),
					globalAttributeSchema.getSortableInScopes(),
					globalAttributeSchema.isLocalized(),
					globalAttributeSchema.isNullable(),
					globalAttributeSchema.isRepresentative(),
					(Class) globalAttributeSchema.getType(),
					globalAttributeSchema.getDefaultValue(),
					globalAttributeSchema.getIndexedDecimalPlaces()
				);
			}
		} else {
			throw new GenericEvitaInternalError("Unexpected input!");
		}
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		Assert.isPremiseValid(catalogSchema != null, "Catalog schema is mandatory!");
		final GlobalAttributeSchemaContract existingAttributeSchema = catalogSchema.getAttribute(this.name)
			.orElseThrow(() -> new InvalidSchemaMutationException(
				"The attribute `" + this.name + "` is not defined in catalog `" + catalogSchema.getName() + "` schema!"
			));

		final GlobalAttributeSchemaContract updatedAttributeSchema = mutate(catalogSchema, existingAttributeSchema, GlobalAttributeSchemaContract.class);
		return replaceAttributeIfDifferent(
			catalogSchema, existingAttributeSchema, updatedAttributeSchema, entitySchemaAccessor,
			// this leads to refresh of the attribute schema
			new UseGlobalAttributeSchemaMutation(this.name)
		);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.UPSERT;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Override
	public String toString() {
		return "Set attribute `" + this.name + "` schema: " +
			", uniqueGlobally=(" + (Arrays.stream(this.uniqueGloballyInScopes).map(it -> it.scope() + ": " + it.uniquenessType().name())) + ")";
	}

}
