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

package io.evitadb.api.requestResponse.schema.mutation.catalog;

import io.evitadb.api.EntityCollectionContract;
import io.evitadb.api.EvitaSessionContract;
import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.dataType.ClassifierType;
import io.evitadb.utils.Assert;
import io.evitadb.utils.ClassifierUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Mutation is responsible for setting up a new {@link EntitySchemaContract} - or more precisely
 * the {@link EntityCollectionContract} instance within {@link io.evitadb.api.CatalogContract} instance.
 * The mutation is used by {@link io.evitadb.api.CatalogContract#getOrCreateCollectionForEntity(EvitaSessionContract, String)} method
 * internally.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
public class CreateEntitySchemaMutation
	implements LocalCatalogSchemaMutation, CatalogSchemaMutation, EntitySchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = 5167037327442001715L;
	@Nonnull @Getter private final String name;

	public CreateEntitySchemaMutation(@Nonnull String name) {
		ClassifierUtils.validateClassifierFormat(ClassifierType.ENTITY, name);
		this.name = name;
	}

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaProvider entitySchemaAccessor
	) {
		if (entitySchemaAccessor instanceof MutationEntitySchemaAccessor mutationEntitySchemaAccessor) {
			mutationEntitySchemaAccessor.addUpsertedEntitySchema(EntitySchema._internalBuild(this.name));
		}
		Assert.isPremiseValid(
			catalogSchema != null,
			"Catalog schema cannot be null when creating entity schema mutation!"
		);
		return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Nonnull
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		return EntitySchema._internalBuild(this.name);
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

	@Nonnull
	@Override
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();
		context.setEntityType(this.name);

		return LocalCatalogSchemaMutation.super.toChangeCatalogCapture(
			predicate,
			content
		);
	}

	@Override
	public String toString() {
		return "Create entity schema: " +
			"entity type='" + this.name + '\'';
	}
}
