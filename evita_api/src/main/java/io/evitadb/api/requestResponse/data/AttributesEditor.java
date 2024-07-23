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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.requestResponse.data.mutation.attribute.AttributeMutation;
import io.evitadb.api.requestResponse.data.structure.Attributes;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.api.requestResponse.schema.dto.AttributeSchema;
import io.evitadb.api.requestResponse.schema.dto.EntityAttributeSchema;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Contract for classes that allow creating / updating or removing information in {@link Attributes} instance.
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AttributesContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AttributesEditor<W extends AttributesEditor<W, S>, S extends AttributeSchemaContract> extends AttributesContract<S> {

	/**
	 * Removes value associated with the key or null when the attribute is missing.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W removeAttribute(@Nonnull String attributeName);

	/**
	 * Stores value associated with the key.
	 * Setting null value effectively removes the attribute as if the {@link #removeAttribute(String)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAttribute(@Nonnull String attributeName, @Nullable T attributeValue);

	/**
	 * Stores array of values associated with the key.
	 * Setting null value effectively removes the attribute as if the {@link #removeAttribute(String)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAttribute(@Nonnull String attributeName, @Nullable T[] attributeValue);

	/**
	 * Removes locale specific value associated with the key or null when the attribute is missing.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W removeAttribute(@Nonnull String attributeName, @Nonnull Locale locale);

	/**
	 * Stores locale specific value associated with the key.
	 * Setting null value effectively removes the attribute as if the {@link #removeAttribute(String, Locale)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T attributeValue);

	/**
	 * Stores array of locale specific values associated with the key.
	 * Setting null value effectively removes the attribute as if the {@link #removeAttribute(String, Locale)} was called.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	<T extends Serializable> W setAttribute(@Nonnull String attributeName, @Nonnull Locale locale, @Nullable T[] attributeValue);

	/**
	 * Alters attribute value in a way defined by the passed mutation implementation.
	 * There may never me multiple mutations for the same attribute - if you need to compose mutations you must wrap
	 * them into single one, that is then handed to the builder.
	 * <p>
	 * Remember each setAttribute produces a mutation itself - so you cannot set attribute and mutate it in the same
	 * round. The latter operation would overwrite the previously registered mutation.
	 *
	 * @return self (builder pattern)
	 */
	@Nonnull
	W mutateAttribute(@Nonnull AttributeMutation mutation);

	/**
	 * Interface that simply combines writer and builder contracts together.
	 */
	interface AttributesBuilder<S extends AttributeSchemaContract> extends AttributesEditor<AttributesBuilder<S>, S>, BuilderContract<Attributes<S>> {

		@Nonnull
		@Override
		Stream<? extends AttributeMutation> buildChangeSet();

		/**
		 * Returns human readable string representation of the attribute schema location.
		 */
		@Nonnull
		Supplier<String> getLocationResolver();

		/**
		 * Method creates implicit attribute type for the attribute value that doesn't map to any existing (known) attribute
		 * type of the {@link EntitySchemaContract} schema.
		 */
		static EntityAttributeSchemaContract createImplicitEntityAttributeSchema(@Nonnull AttributeValue attributeValue) {
			return EntityAttributeSchema._internalBuild(
				attributeValue.key().attributeName(),
				Objects.requireNonNull(attributeValue.value()).getClass(),
				attributeValue.key().localized()
			);
		}

		/**
		 * Method creates implicit attribute type for the attribute value that doesn't map to any existing (known) attribute
		 * type of the {@link EntitySchemaContract} schema.
		 */
		static AttributeSchemaContract createImplicitReferenceAttributeSchema(@Nonnull AttributeValue attributeValue) {
			return AttributeSchema._internalBuild(
				attributeValue.key().attributeName(),
				Objects.requireNonNull(attributeValue.value()).getClass(),
				attributeValue.key().localized()
			);
		}

	}

}
