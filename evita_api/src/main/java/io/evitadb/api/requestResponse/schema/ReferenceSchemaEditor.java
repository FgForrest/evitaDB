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

package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.query.filter.FacetHaving;
import io.evitadb.api.query.filter.ReferenceHaving;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.requestResponse.extraResult.FacetSummary.FacetStatistics;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link ReferenceSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface ReferenceSchemaEditor<S extends ReferenceSchemaEditor<S>> extends
	ReferenceSchemaContract,
	NamedSchemaWithDeprecationEditor<S>,
	AttributeProviderSchemaEditor<S, AttributeSchemaContract, AttributeSchemaEditor.AttributeSchemaBuilder>,
	SortableAttributeCompoundSchemaProviderEditor<S>
{

	/**
	 * Specifies that reference of this type will be related to external entity not maintained in Evita.
	 */
	S withGroupType(@Nonnull String groupType);

	/**
	 * Specifies that reference of this type will be related to another entity maintained in Evita ({@link Entity#getType()}).
	 */
	S withGroupTypeRelatedToEntity(@Nonnull String groupType);

	/**
	 * Specifies that this reference will not be grouped to a specific groups. This is default setting for the reference.
	 */
	S withoutGroupType();

	/**
	 * Contains TRUE if evitaDB should create and maintain searchable index for this reference allowing to filter by
	 * {@link ReferenceHaving} filtering constraints. Index is also required when reference is {@link #faceted()}.
	 *
	 * Do not mark reference as faceted unless you know that you'll need to filter entities by this reference. Each
	 * indexed reference occupies (memory/disk) space in the form of index. When reference is not indexed, the entity
	 * cannot be looked up by reference attributes or relation existence itself, but the data is loaded alongside
	 * other references and is available by calling {@link SealedEntity#getReferences()} method.
	 */
	/* TODO JNO - change this to "indexed" + verify no attribute can be marked as filterable/sortable/compound on non-indexed reference */
	S filterable();

	/**
	 * Makes reference as non-faceted. This means reference information will be available on entity when loaded but
	 * cannot be used in filtering.
	 */
	S nonFilterable();

	/**
	 * Makes reference faceted. That means that statistics data for this reference should be maintained and this
	 * allowing to get {@link FacetStatistics} for this reference or use {@link FacetHaving} filtering query. When
	 * reference is faceted it is also automatically made {@link #filterable()} as well.
	 *
	 * Do not mark reference as faceted unless you know that you'll need to filter entities by this reference. Each
	 * indexed reference occupies (memory/disk) space in the form of index.
	 */
	S faceted();

	/**
	 * Makes reference as non-faceted. This means reference information will be available on entity when loaded but
	 * cannot be part of the computed facet statistics and filtering by facet query.
	 *
	 * @return builder to continue with configuration
	 */
	S nonFaceted();

	/**
	 * Interface that simply combines {@link ReferenceSchemaEditor} and {@link ReferenceSchemaContract} entity contracts
	 * together. Builder produces either {@link EntitySchemaMutation} that describes all changes to be made on
	 * {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface ReferenceSchemaBuilder extends ReferenceSchemaEditor<ReferenceSchemaBuilder> {

		/**
		 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#getVersion()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 */
		@Nonnull
		Collection<EntitySchemaMutation> toMutation();

	}
}
