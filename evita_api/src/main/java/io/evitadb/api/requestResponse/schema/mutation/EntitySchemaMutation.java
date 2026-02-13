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

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;

/**
 * Marks all implementations that alter the {@link EntitySchemaContract}. This interface sits in the schema mutation
 * hierarchy between the root {@link SchemaMutation} interface and the more specialized
 * {@link LocalEntitySchemaMutation} subinterface.
 *
 * **Schema Mutation Hierarchy**
 *
 * Entity schema mutations are part of the broader schema mutation hierarchy:
 *
 * - {@link SchemaMutation} (root interface for all schema mutations)
 * - {@link CatalogSchemaMutation} (for catalog-level mutations)
 * - {@link EntitySchemaMutation} (this interface, for entity-level mutations)
 * - {@link LocalEntitySchemaMutation} (for mutations on already-identified entity schema instances)
 *
 * **Mutate Contract**
 *
 * The {@link #mutate(CatalogSchemaContract, EntitySchemaContract)} method applies the mutation operation and returns
 * the modified entity schema. Different mutation types follow different patterns:
 *
 * - **Create operations**: Accept `null` `entitySchema` input and produce a non-null result
 * - **Remove operations**: Accept non-null `entitySchema` input and may produce `null` result
 * - **Modification operations**: Always accept and produce non-null values
 *
 * The `catalogSchema` parameter provides access to the owner catalog schema, which is necessary for mutations that
 * need to reference shared global attributes from {@link CatalogSchemaContract#getAttributes()} or validate entity
 * schema changes against catalog-level constraints.
 *
 * **Usage Context**
 *
 * Entity schema mutations are used to modify entity type definitions within a catalog, including:
 *
 * - Entity-level settings (hierarchy, pricing, locales, currencies, evolution modes)
 * - Attribute schemas (entity attributes, reference attributes)
 * - Reference schemas (relationships to other entity types)
 * - Associated data schemas (non-indexed structured data)
 * - Sortable attribute compound schemas (composite sort keys)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@Immutable
@ThreadSafe
public interface EntitySchemaMutation extends SchemaMutation {

	/**
	 * Applies the mutation operation on the entity schema in the input and returns the modified version as its return
	 * value. The create operation works with `null` `entitySchema` input and produces non-null result. The remove
	 * operation produces the opposite. Modification operations always accept and produce non-null values.
	 *
	 * @param catalogSchema owner catalog schema that contains shared global attributes accessible via
	 *                      {@link CatalogSchemaContract#getAttributes()} and other catalog-level constraints
	 * @param entitySchema  current version of the entity schema as an input to mutate (may be null for create
	 *                      operations)
	 * @return the modified entity schema, or null for remove operations that eliminate the entity schema
	 */
	@Nullable
	EntitySchemaContract mutate(
		@Nonnull CatalogSchemaContract catalogSchema,
		@Nullable EntitySchemaContract entitySchema
	);

}
