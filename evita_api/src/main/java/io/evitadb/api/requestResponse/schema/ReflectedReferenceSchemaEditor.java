/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link ReflectedReferenceSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public interface ReflectedReferenceSchemaEditor<S extends ReflectedReferenceSchemaEditor<S>> extends ReferenceSchemaEditor<S> {

	String GROUP_TYPE_EXCEPTION_MESSAGE = "Group type can be set only on original reference. It makes no sense to change it on reflected one.";

	/**
	 * Specifies that the description of the reflected reference is inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withDescriptionInherited();

	/**
	 * Specifies that the deprecated flag of the reflected reference is inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withDeprecatedInherited();

	/**
	 * Specifies the cardinality of the reflected reference.
	 *
	 * @param cardinality the cardinality of the reflected reference
	 * @return the builder to continue with configuration
	 */
	S withCardinality(@Nonnull Cardinality cardinality);

	/**
	 * Specifies that the cardinality of the reflected reference is inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withCardinalityInherited();

	/**
	 * Specifies that the attributes of the reflected reference are inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withAttributesInherited();

	/**
	 * Specifies that none attributes of the reflected reference are inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withoutAttributesInherited();

	/**
	 * Specifies that the attributes of the reflected reference are inherited from the target reference, except for the
	 * specified attribute names.
	 *
	 * @param attributeNames attribute names that should not be inherited
	 * @return this
	 */
	@Nonnull
	S withAttributesInheritedExcept(@Nonnull String... attributeNames);

	/**
	 * Specifies that {@link ReferenceSchemaContract#isIndexed()} property settings is inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withIndexedInherited();

	/**
	 * Specifies that {@link ReferenceSchemaContract#isFaceted()} property settings is inherited from the target reference.
	 *
	 * @return this
	 */
	@Nonnull
	S withFacetedInherited();

	/**
	 * Group type can be set only on original reference. It makes no sense to change it on reflected one.
	 *
	 * @return this
	 */
	@Override
	default S withGroupType(@Nonnull String groupType) {
		throw new UnsupportedOperationException(GROUP_TYPE_EXCEPTION_MESSAGE);
	}

	/**
	 * Group type can be set only on original reference. It makes no sense to change it on reflected one.
	 *
	 * @return this
	 */
	@Override
	default S withGroupTypeRelatedToEntity(@Nonnull String groupType) {
		throw new UnsupportedOperationException(GROUP_TYPE_EXCEPTION_MESSAGE);
	}

	/**
	 * Group type can be set only on original reference. It makes no sense to change it on reflected one.
	 *
	 * @return this
	 */
	@Override
	default S withoutGroupType() {
		throw new UnsupportedOperationException(GROUP_TYPE_EXCEPTION_MESSAGE);
	}

	/**
	 * Interface that simply combines {@link ReflectedReferenceSchemaEditor} and {@link ReflectedReferenceSchemaContract}
	 * entity contracts together. Builder produces either {@link EntitySchemaMutation} that describes all changes to be
	 * made on {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface ReflectedReferenceSchemaBuilder extends ReflectedReferenceSchemaEditor<ReflectedReferenceSchemaEditor.ReflectedReferenceSchemaBuilder> {

		/**
		 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 */
		@Nonnull
		Collection<LocalEntitySchemaMutation> toMutation();

	}

}
