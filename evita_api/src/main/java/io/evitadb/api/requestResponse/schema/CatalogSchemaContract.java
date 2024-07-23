/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.CatalogContract;
import io.evitadb.api.exception.SchemaAlteringException;
import io.evitadb.api.requestResponse.data.ContentComparator;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.dto.EntitySchemaProvider;
import io.evitadb.exception.EvitaInvalidUsageException;

import javax.annotation.Nonnull;
import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/**
 * Catalog schema defines the basic properties of the {@link CatalogContract} that is a main container
 * of evitaDB data comparable to the single schema/database in a relational database system. Single catalog usually
 * contains data of single customer and represents a distinguishing unit in multi-tenant system.
 *
 * Catalog is uniquely identified by its {@link #getName()} among all other catalogs in the same evitaDB engine.
 * The instance of the catalog is represented by the {@link CatalogContract}.
 *
 * @see  CatalogContract
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface CatalogSchemaContract
	extends
	Versioned,
	NamedSchemaContract,
	ContentComparator<CatalogSchemaContract>,
	EntitySchemaProvider,
	AttributeSchemaProvider<GlobalAttributeSchemaContract> {

	/**
	 * Returns set of allowed evolution modes. These allow to specify how strict is evitaDB when unknown information is
	 * presented to her for the first time. When no evolution mode is set, each violation of the {@link EntitySchemaContract} is
	 * reported by an exception. This behaviour can be changed by this evolution mode, however.
	 */
	@Nonnull
	Set<CatalogEvolutionMode> getCatalogEvolutionMode();

	/**
	 * Returns a collection of all entity schemas in catalog.
	 *
	 * @return a collection of entity schemas in catalog
	 */
	@Nonnull
	Collection<EntitySchemaContract> getEntitySchemas();

	/**
	 * Returns entity schema that is connected with passed `entityType` or NULL if such entity collection doesn't
	 * exist.
	 */
	@Nonnull
	Optional<EntitySchemaContract> getEntitySchema(@Nonnull String entityType);

	/**
	 * Returns entity schema that is connected with passed `entityType` or throws an exception if such entity collection
	 * doesn't exist.
	 */
	@Nonnull
	default EntitySchemaContract getEntitySchemaOrThrowException(@Nonnull String entityType) {
		return getEntitySchema(entityType)
			.orElseThrow(() -> new EvitaInvalidUsageException("Schema for entity with name `" + entityType + "` was not found!"));
	}

	/**
	 * Validates the current state of the object. If the object is not valid, {@link SchemaAlteringException} is thrown.
	 * Method validates all entity schemas using {@link EntitySchemaContract#validate(CatalogSchemaContract)}.
	 *
	 * @throws SchemaAlteringException if current schema contains validation errors
	 */
	void validate() throws SchemaAlteringException;

}
