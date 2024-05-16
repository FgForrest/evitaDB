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
import io.evitadb.api.requestResponse.schema.mutation.AttributeSchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.EntitySchemaMutation;
import io.evitadb.api.requestResponse.schema.mutation.ReferenceSchemaMutation;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Collection;
import java.util.function.BooleanSupplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AttributeSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeSchemaEditor<T extends AttributeSchemaEditor<T>> extends
	NamedSchemaWithDeprecationEditor<T>,
	AttributeSchemaContract
{
	/**
	 * Default value is used when the entity is created without this attribute specified. Default values allow to pass
	 * non-null checks even if no attributes of such name are specified.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T withDefaultValue(@Nullable Serializable defaultValue);

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T filterable();

	/**
	 * When attribute is filterable, it is possible to filter entities by this attribute. Do not mark attribute
	 * as filterable unless you know that you'll search entities by this attribute. Each filterable attribute occupies
	 * (memory/disk) space in the form of index.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * @param decider returns true when attribute should be filtered
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T filterable(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T unique();

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 *
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * @param decider returns true when attribute should be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T unique(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute.
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #unique()} in that it is possible to have multiple entities with same value
	 * of this attribute as long as the attribute is {@link #isLocalized()} and the values relate to different locales.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueWithinLocale();

	/**
	 * When attribute is unique it is automatically filterable, and it is ensured there is exactly one single entity
	 * having certain value of this attribute among other entities in the same collection.
	 *
	 *
	 * The attribute will be filtered / looked up for by its {@link AttributeSchemaContract#getType() type}
	 * {@link Comparable} contract. If the type is not {@link Comparable} the {@link String#compareTo(String)}
	 * comparison on its {@link Object#toString()} will be used
	 *
	 * As an example of unique attribute can be EAN - there is no sense in having two entities with same EAN, and it's
	 * better to have this ensured by the database engine.
	 *
	 * This method differs from {@link #unique(BooleanSupplier)} in that it is possible to have multiple entities with
	 * same value of this attribute as long as the attribute is {@link #isLocalized()} and the values relate
	 * to different locales.
	 *
	 * @param decider returns true when attribute should be unique
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T uniqueWithinLocale(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchemaContract#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T sortable();

	/**
	 * When attribute is sortable, it is possible to sort entities by this attribute. Do not mark attribute
	 * as sortable unless you know that you'll sort entities along this attribute. Each sortable attribute occupies
	 * (memory/disk) space in the form of index. {@link AttributeSchemaContract#getType() Type} of the filterable attribute must
	 * implement {@link Comparable} interface.
	 *
	 * @param decider returns true when attribute should be sortable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T sortable(@Nonnull BooleanSupplier decider);

	/**
	 * Localized attribute has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T localized();

	/**
	 * Localized attribute has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @param decider returns true when attribute should be localized
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T localized(@Nonnull BooleanSupplier decider);

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nullable();

	/**
	 * When attribute is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @param decider returns true when attribute should be nullable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T nullable(@Nonnull BooleanSupplier decider);

	/**
	 * Determines how many fractional places are important when entities are compared during filtering or sorting. It is
	 * essential to know that all values of this attribute will be converted to {@link Integer}, so the attribute
	 * number must not ever exceed maximum limits of {@link Integer} type when scaling the number by the power
	 * of ten using `indexDecimalPlaces` as exponent.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	T indexDecimalPlaces(int indexedDecimalPlaces);

	/**
	 * Interface that simply combines {@link AttributeSchemaEditor} and {@link AttributeSchemaContract} entity contracts
	 * together. Builder produces either {@link EntitySchemaMutation} that describes all changes to be made on
	 * {@link EntitySchemaContract} instance to get it to "up-to-date" state or can provide already built
	 * {@link EntitySchemaContract} that may not represent globally "up-to-date" state because it is based on
	 * the version of the entity known when builder was created.
	 *
	 * Mutation allows Evita to perform surgical updates on the latest version of the {@link EntitySchemaContract}
	 * object that is in the database at the time update request arrives.
	 */
	interface AttributeSchemaBuilder extends AttributeSchemaEditor<AttributeSchemaBuilder> {

		/**
		 * Returns collection of {@link EntitySchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link AttributeSchemaMutation} and can be retrieved by calling
		 * {@link #toAttributeMutation()} identically.
		 */
		@Nonnull
		Collection<EntitySchemaMutation> toMutation();

		/**
		 * Returns collection of {@link AttributeSchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing parent schema in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link EntitySchemaMutation} and can be retrieved by calling
		 * {@link #toMutation()} identically.
		 */
		@Nonnull
		Collection<AttributeSchemaMutation> toAttributeMutation();

		/**
		 * Returns collection of {@link ReferenceSchemaMutation} instances describing what changes occurred in the builder
		 * and which should be applied on the existing {@link ReferenceSchemaContract} in particular version.
		 * Each mutation increases {@link Versioned#version()} of the modified object and allows to detect race
		 * conditions based on "optimistic locking" mechanism in very granular way.
		 *
		 * All mutations need and will also to implement {@link AttributeSchemaMutation} and can be retrieved by calling
		 * {@link #toAttributeMutation()} identically.
		 */
		@Nonnull
		Collection<ReferenceSchemaMutation> toReferenceMutation(@Nonnull String referenceName);

		/**
		 * Returns built "local up-to-date" {@link AttributeSchemaContract} instance that may not represent globally
		 * "up-to-date" state because it is based on the version of the entity known when builder was created.
		 *
		 * This method is particularly useful for tests.
		 */
		@Nonnull
		AttributeSchemaContract toInstance();

	}
}
