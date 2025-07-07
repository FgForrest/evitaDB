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

package io.evitadb.api.requestResponse.schema.mutation;

import io.evitadb.api.requestResponse.cdc.ChangeCaptureContent;
import io.evitadb.api.requestResponse.cdc.ChangeCatalogCapture;
import io.evitadb.api.requestResponse.data.mutation.LocalMutation;
import io.evitadb.api.requestResponse.mutation.MutationPredicate;
import io.evitadb.api.requestResponse.mutation.MutationPredicateContext;
import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.mutation.catalog.MutationEntitySchemaAccessor;
import io.evitadb.api.requestResponse.schema.mutation.engine.ModifyCatalogSchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * This interface marks all {@link CatalogSchemaMutation} that can be locally applicable to an already identified
 * schema instance. These schemas don't provide the target catalog name by themselves and need to be wrapped inside
 * {@link ModifyCatalogSchemaMutation}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface LocalCatalogSchemaMutation extends CatalogSchemaMutation {

	@Nullable
	@Override
	default CatalogSchemaWithImpactOnEntitySchemas mutate(@Nullable CatalogSchemaContract catalogSchema) {
		return mutate(
			Objects.requireNonNull(catalogSchema),
			MutationEntitySchemaAccessor.INSTANCE
		);
	}

	/**
	 * Method applies the mutation operation on the catalog schema in the input and returns modified version
	 * as its return value. The create operation works with NULL input value and produces non-NULL result, the remove
	 * operation produces the opposite. Modification operations always accept and produce non-NULL values.
	 *
	 * @param catalogSchema current version of the schema as an input to mutate
	 * @param entitySchemaAccessor entity schema provider allowing to access list of entity schemas in the catalog
	 */
	@Nullable
	CatalogSchemaWithImpactOnEntitySchemas mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nonnull EntitySchemaProvider entitySchemaAccessor
	);

	/**
	 * In this method we override the default implementation to move the index of the {@link MutationPredicateContext}
	 * as each LocalCatalogSchemaMutation is a separate mutation unit.
	 *
	 * @param predicate the predicate to be used for filtering the {@link LocalMutation} mutation items if any
	 *                  are present
	 * @param content   the requested content of the capture
	 * @return default implementation of the {@link ChangeCatalogCapture} stream
	 */
	@Override
	@Nonnull
	default Stream<ChangeCatalogCapture> toChangeCatalogCapture(
		@Nonnull MutationPredicate predicate,
		@Nonnull ChangeCaptureContent content
	) {
		predicate.getContext().advance();
		return CatalogSchemaMutation.super.toChangeCatalogCapture(predicate, content);
	}

}
