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

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.cdc.Operation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.CatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.NamedSchemaMutation;
import io.evitadb.exception.GenericEvitaInternalError;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.stream.Stream;

/**
 * Mutation is responsible for removing an existing {@link EntitySchemaContract} - or more precisely the entity
 * collection instance itself.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@ThreadSafe
@Immutable
@EqualsAndHashCode
@AllArgsConstructor
public class RemoveEntitySchemaMutation
	implements LocalCatalogSchemaMutation, CatalogSchemaMutation, EntitySchemaMutation, NamedSchemaMutation {
	@Serial private static final long serialVersionUID = -1294172811385202717L;
	@Getter @Nonnull private final String name;

	@Nullable
	@Override
	public CatalogSchemaWithImpactOnEntitySchemas mutate(@Nonnull CatalogSchemaContract catalogSchema, @Nonnull EntitySchemaProvider entitySchemaAccessor) {
		if (entitySchemaAccessor instanceof MutationEntitySchemaAccessor mutationEntitySchemaAccessor) {
			mutationEntitySchemaAccessor
				.getEntitySchema(this.name)
				.ifPresentOrElse(
					it -> mutationEntitySchemaAccessor.removeEntitySchema(this.name),
					() -> {
						throw new GenericEvitaInternalError("Entity schema not found: " + this.name);
					}
				);
		}
		// do nothing - we alter only the entity schema
		return new CatalogSchemaWithImpactOnEntitySchemas(catalogSchema);
	}

	@Nonnull
	@Override
	public Operation operation() {
		return Operation.REMOVE;
	}

	@Nonnull
	@Override
	public String containerName() {
		return this.name;
	}

	@Nullable
	@Override
	public EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	) {
		return null;
	}

	@Override
	@Nonnull
	public Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		final MutationPredicateContext context = predicate.getContext();
		context.setEntityType(this.name);
		return LocalCatalogSchemaMutation.super.toChangeCatalogCapture(predicate, content);
	}

	@Override
	public String toString() {
		return "Remove entity schema: " +
			"name='" + this.name + '\'';
	}
}
