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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract;
import io.evitadb.utils.NamingConvention;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.evitadb.utils.Assert.notNull;

/**
 * Internal implementation of {@link SortableAttributeCompoundSchemaContract}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Immutable
@ThreadSafe
public class SortableAttributeCompoundSchema implements SortableAttributeCompoundSchemaContract {
	@Serial private static final long serialVersionUID = 1640604843846015381L;
	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final List<AttributeElement> attributeElements;
	private Boolean memoizedLocalized;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static SortableAttributeCompoundSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull List<AttributeElement> attributeElements
	) {
		return new SortableAttributeCompoundSchema(
			name,
			NamingConvention.generate(name),
			description,
			deprecationNotice,
			attributeElements
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of ReferenceSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static SortableAttributeCompoundSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull List<AttributeElement> attributeElements
	) {
		return new SortableAttributeCompoundSchema(
			name,
			nameVariants,
			description,
			deprecationNotice,
			attributeElements
		);
	}

	private SortableAttributeCompoundSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull List<AttributeElement> attributeElements
	) {
		this.name = name;
		this.nameVariants = Collections.unmodifiableMap(nameVariants);
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.attributeElements = Collections.unmodifiableList(attributeElements);
	}

	/**
	 * The method returns true if any of the referenced attributes is localized. The return value is memoized, so that
	 * next time the method is called, the result is returned immediately.
	 * Part of PRIVATE API, because we need to ensure the `attributeSchemaProvider` always provide the same and correct
	 * attribute schemas from the same entity schema version.
	 */
	public boolean isLocalized(@Nonnull Function<String, AttributeSchema> attributeSchemaProvider) {
		if (this.memoizedLocalized == null) {
			this.memoizedLocalized = attributeElements
				.stream()
				.anyMatch(it -> {
					final AttributeSchemaContract attributeSchema = attributeSchemaProvider.apply(it.attributeName());
					notNull(attributeSchema, "Attribute `" + it.attributeName() + "` schema not found!");
					return attributeSchema.isLocalized();
				});
		}
		return this.memoizedLocalized;
	}

	@Nonnull
	@Override
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return nameVariants.get(namingConvention);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		SortableAttributeCompoundSchema that = (SortableAttributeCompoundSchema) o;

		if (!name.equals(that.name)) return false;
		if (!Objects.equals(description, that.description)) return false;
		if (!Objects.equals(deprecationNotice, that.deprecationNotice))
			return false;
		return attributeElements.equals(that.attributeElements);
	}

	@Override
	public int hashCode() {
		int result = name.hashCode();
		result = 31 * result + (description != null ? description.hashCode() : 0);
		result = 31 * result + (deprecationNotice != null ? deprecationNotice.hashCode() : 0);
		result = 31 * result + attributeElements.hashCode();
		return result;
	}

	@Override
	public String toString() {
		return '@' + name + ": " +
			attributeElements
				.stream()
				.map(AttributeElement::toString)
				.collect(Collectors.joining(", "));
	}
}
