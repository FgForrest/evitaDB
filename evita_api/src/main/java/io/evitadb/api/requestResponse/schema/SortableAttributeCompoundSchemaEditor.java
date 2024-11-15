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

import io.evitadb.api.requestResponse.data.Versioned;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.LocalEntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.SortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link SortableAttributeCompoundSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SortableAttributeCompoundSchemaEditor<S extends SortableAttributeCompoundSchemaEditor<S>> extends
	SortableAttributeCompoundSchemaContract, NamedSchemaWithDeprecationEditor<S> {

	/**
	 * Makes evitaDB create and maintain sortable index for this attribute compound allowing to order entities by it.
	 *
	 * This method makes sortable attribute compound indexed only in the default (e.g. {@link Scope#LIVE}) scope.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default S indexed() {
		return indexed(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Makes evitaDB create and maintain sortable index for this attribute compound allowing to order entities by it.
	 *
	 * This method makes sortable attribute compound indexed in specified set of scopes.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	S indexed(@Nullable Scope... inScope);

	/**
	 * Makes evitaDB drop sortable index for this attribute compound effectively preventing to order entities by it.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default S nonIndexed() {
		return nonIndexed(Scope.values());
	}

	/**
	 * Makes evitaDB drop sortable index for this attribute compound in specified set of scopes effectively preventing
	 * to order entities by it in that scope.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	S nonIndexed(@Nullable Scope... inScope);

	/**
	 * Interface that simply combines {@link SortableAttributeCompoundSchemaEditor} and
	 * {@link SortableAttributeCompoundSchemaContract} contracts together. Builder produces either
	 * {@link EntitySchemaMutation} that describes all changes to be made on {@link EntitySchemaContract} instance to
	 * get it to "up-to-date" state or can provide already built {@link EntitySchemaContract} that may not represent
	 * globally "up-to-date" state because it is based on the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface SortableAttributeCompoundSchemaBuilder extends SortableAttributeCompoundSchemaEditor<SortableAttributeCompoundSchemaBuilder> {

		/**
		 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 */
		@Nonnull
		Collection<LocalEntitySchemaMutation> toMutation();

		/**
		 * Returns collection of {@link SortableAttributeCompoundSchemaMutation} instances describing what changes
		 * occurred in the builder and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link EntitySchemaMutation} and can be retrieved by calling
		 * {@link #toMutation()} identically.
		 */
		@Nonnull
		Collection<SortableAttributeCompoundSchemaMutation> toSortableAttributeCompoundSchemaMutation();

		/**
		 * Returns collection of {@link ReferenceSchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing {@link ReferenceSchemaContract} in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link SortableAttributeCompoundSchemaMutation} and can be retrieved by calling
		 * {@link #toSortableAttributeCompoundSchemaMutation()} identically.
		 */
		@Nonnull
		Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName);

		/**
		 * Returns built "local up-to-date" {@link SortableAttributeCompoundSchemaContract} instance that may not
		 * represent globally "up-to-date" state because it is based on the version of the entity known when builder was
		 * created.
		 *
		 * This method is particularly useful for tests.
		 */
		@Nonnull
		SortableAttributeCompoundSchemaContract toInstance();

	}

}
