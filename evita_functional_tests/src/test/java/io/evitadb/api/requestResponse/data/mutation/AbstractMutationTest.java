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

package io.evitadb.api.requestResponse.data.mutation;

import io.evitadb.api.APITestConstants;
import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.CatalogEvolutionMode;
import io.evitadb.api.requestResponse.schema.CatalogSchemaDecorator;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.EvolutionMode;
import io.evitadb.api.requestResponse.schema.SealedCatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.CatalogSchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchema;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.api.requestResponse.schema.dto.ReferenceIndexType;
import io.evitadb.api.requestResponse.schema.dto.ReferenceSchema;
import io.evitadb.api.requestResponse.schema.mutation.reference.ScopedReferenceIndexType;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Base mutation class that contains initalized schemas for testing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public abstract class AbstractMutationTest {
	protected final EntitySchema productSchema = EntitySchema._internalBuild(
		1, "PRODUCT",
		null, null,
		true,
		false, Scope.NO_SCOPE,
		true, new Scope[] { Scope.LIVE }, 0,
		Collections.emptySet(),
		Collections.emptySet(),
		Collections.emptyMap(),
		Collections.emptyMap(),
		Collections.singletonMap(
			"brand",
			ReferenceSchema._internalBuild(
				"brand",
				"brand", false,
				Cardinality.ZERO_OR_ONE,
				null, false,
				new ScopedReferenceIndexType[] {
					new ScopedReferenceIndexType(Scope.DEFAULT_SCOPE, ReferenceIndexType.FOR_FILTERING)
				},
				new Scope[] {Scope.LIVE}
			)
		),
		EnumSet.allOf(EvolutionMode.class),
		Collections.emptyMap()
	);
	protected final Map<String, EntitySchemaContract> entitySchemas = Collections.singletonMap(
		productSchema.getName(), productSchema
	);
	protected final SealedCatalogSchema catalogSchema = new CatalogSchemaDecorator(
		CatalogSchema._internalBuild(
			APITestConstants.TEST_CATALOG,
			Collections.emptyMap(),
			EnumSet.allOf(CatalogEvolutionMode.class),
			new EntitySchemaProvider() {
				@Nonnull
				@Override
				public Collection<EntitySchemaContract> getEntitySchemas() {
					return entitySchemas.values();
				}

				@Nonnull
				@Override
				public Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType) {
					return ofNullable(entitySchemas.get(entityType));
				}
			}
		)
	);

}
