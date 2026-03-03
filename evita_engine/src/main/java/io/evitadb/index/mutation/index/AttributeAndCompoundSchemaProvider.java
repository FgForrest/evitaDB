/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.index.mutation.index;


import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema;

import javax.annotation.Nonnull;
import java.util.stream.Stream;

/**
 * A sealed abstraction over the two schema contexts that supply attribute and sortable compound schema definitions
 * during index mutation processing.
 *
 * The two permitted implementations cover exactly the two scopes in which attributes can appear in evitaDB:
 *
 * - {@link EntitySchemaAttributeAndCompoundSchemaProvider} — wraps an
 *   {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract} and is used when mutating entity-level
 *   attributes (i.e. attributes that belong directly to the entity, not to one of its references).
 * - {@link ReferenceSchemaAttributeAndCompoundSchemaProvider} — wraps a
 *   {@link io.evitadb.api.requestResponse.schema.ReferenceSchemaContract} together with the parent
 *   {@link io.evitadb.api.requestResponse.schema.EntitySchemaContract} (used for error context) and is used when
 *   mutating reference-level attributes.
 *
 * The interface is consumed exclusively by {@link AttributeIndexMutator} static methods, which are kept in a
 * separate interface to avoid bloating {@link EntityIndexLocalMutationExecutor}. Callers pass an appropriate
 * provider instance instead of carrying both schema objects through every call-site, keeping the mutation API
 * uniform regardless of whether the attribute belongs to an entity or a reference.
 *
 * Within {@link AttributeIndexMutator} this provider is used in three distinct ways:
 *
 * - as a method reference `attributeSchemaProvider::getAttributeSchema` passed to
 *   `{@link io.evitadb.api.requestResponse.schema.dto.SortableAttributeCompoundSchema#isLocalized}` to determine
 *   whether a compound index must be maintained per-locale
 * - as a lambda `theAttributeName -> attributeSchemaProvider.getAttributeSchema(theAttributeName).getPlainType()`
 *   passed to `EntityIndex#insertSortAttributeCompound` to resolve the plain Java type of each constituent attribute
 * - directly via `getAttributeSchema(attributeName)` to obtain the full schema before applying any filter, sort,
 *   or unique index mutation
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public sealed interface AttributeAndCompoundSchemaProvider permits EntitySchemaAttributeAndCompoundSchemaProvider, ReferenceSchemaAttributeAndCompoundSchemaProvider {

	/**
	 * Returns the internal `{@link AttributeSchema}` DTO for the named attribute from the underlying schema context.
	 *
	 * The returned object exposes the concrete DTO type (rather than the contract interface) because
	 * {@link AttributeIndexMutator} needs access to implementation-specific methods such as
	 * `{@link AttributeSchema#getPlainType()}` that are not part of the public API contract.
	 *
	 * This method is called before every index mutation that touches a named attribute — for upserts, removals,
	 * and numeric-delta mutations alike — to determine the attribute's indexing characteristics (filterable,
	 * sortable, unique, globally unique) and its target Java type for value coercion.
	 *
	 * @param attributeName the exact name of the attribute as declared in the schema
	 * @return the attribute schema DTO for the given name
	 * @throws io.evitadb.api.exception.AttributeNotFoundException if no attribute with that name exists in the
	 *         underlying schema context; the exception message includes the schema name for diagnostics
	 */
	@Nonnull
	AttributeSchema getAttributeSchema(@Nonnull String attributeName);

	/**
	 * Returns a stream of all {@link SortableAttributeCompoundSchema} definitions from the underlying schema context
	 * whose constituent attribute list includes the named attribute.
	 *
	 * This method drives the "cascade update" logic in {@link AttributeIndexMutator}: whenever a single attribute
	 * value changes, every compound sort index that references that attribute must also be rebuilt. The caller
	 * iterates the returned stream and, for each compound, removes the old composite sort key and inserts a new
	 * one reflecting the changed attribute value.
	 *
	 * The stream is derived from
	 * `{@link io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaProvider
	 * #getSortableAttributeCompoundsForAttribute}` and cast to the internal DTO type, so all elements are
	 * guaranteed to be non-null concrete {@link SortableAttributeCompoundSchema} instances.
	 *
	 * @param attributeName the exact name of the attribute for which related compound schemas are requested
	 * @return a stream of compound schemas that contain the given attribute; empty if the attribute participates in
	 *         no sortable compounds in the underlying schema context
	 */
	@Nonnull
	Stream<SortableAttributeCompoundSchema> getCompoundAttributeSchemas(@Nonnull String attributeName);

}
