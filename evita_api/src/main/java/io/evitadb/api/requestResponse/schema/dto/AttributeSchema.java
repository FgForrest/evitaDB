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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Map;


/**
 * Internal implementation of {@link AttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public sealed class AttributeSchema implements AttributeSchemaContract permits GlobalAttributeSchema {
	@Serial private static final long serialVersionUID = 1340876688998990217L;

	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter private final boolean unique;
	@Getter private final boolean filterable;
	@Getter private final boolean sortable;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nullable private final Serializable defaultValue;
	@Getter @Nonnull private final Class<? extends Serializable> plainType;
	@Getter private final int indexedDecimalPlaces;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static AttributeSchema _internalBuild(@Nonnull String name, @Nonnull Class<? extends Serializable> type, boolean localized) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			false, false, false, localized, false,
			type, null,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> AttributeSchema _internalBuild(@Nonnull String name, boolean unique, boolean filterable, boolean sortable, boolean localized, boolean nullable, @Nonnull Class<T> type, @Nullable T defaultValue) {
		if ((filterable || sortable) && BigDecimal.class.equals(type)) {
			throw new EvitaInvalidUsageException(
				"IndexedDecimalPlaces must be specified for attributes of type BigDecimal (attribute: " + name + ")!"
			);
		}
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			unique, filterable, sortable, localized, nullable,
			type, defaultValue,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> AttributeSchema _internalBuild(@Nonnull String name, @Nullable String description, @Nullable String deprecationNotice, boolean unique, boolean filterable, boolean sortable, boolean localized, boolean nullable, @Nonnull Class<T> type, @Nullable T defaultValue, int indexedDecimalPlaces) {
		return new AttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			unique, filterable, sortable, localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> AttributeSchema _internalBuild(@Nonnull String name, @Nonnull Map<NamingConvention, String> nameVariants, @Nullable String description, @Nullable String deprecationNotice, boolean unique, boolean filterable, boolean sortable, boolean localized, boolean nullable, @Nonnull Class<T> type, @Nullable T defaultValue, int indexedDecimalPlaces) {
		return new AttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			unique, filterable, sortable, localized, nullable,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	<T extends Serializable> AttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		boolean unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		this.name = name;
		this.nameVariants = nameVariants;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.unique = unique;
		this.filterable = filterable;
		this.sortable = sortable;
		this.localized = localized;
		this.nullable = nullable;
		this.type = EvitaDataTypes.toWrappedForm(type);
		//noinspection unchecked
		this.plainType = (Class<? extends Serializable>) (this.type.isArray() ? this.type.getComponentType() : this.type);
		this.defaultValue = EvitaDataTypes.toTargetType(defaultValue, this.plainType);
		this.indexedDecimalPlaces = indexedDecimalPlaces;
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Override
	public String toString() {
		return "AttributeSchema{" +
			"name='" + name + '\'' +
			", unique=" + unique +
			", filterable=" + filterable +
			", sortable=" + sortable +
			", localized=" + localized +
			", nullable=" + nullable +
			", type=" + type +
			", indexedDecimalPlaces=" + indexedDecimalPlaces +
			'}';
	}
}
