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

import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * Internal implementation of {@link EntityAttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(callSuper = true)
public final class EntityAttributeSchema extends AttributeSchema implements EntityAttributeSchemaContract {
	@Serial private static final long serialVersionUID = 8204057625761013999L;

	@Getter private final boolean representative;

	public <T extends Serializable> EntityAttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable AttributeUniquenessType unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		super(
			name, nameVariants, description, deprecationNotice,
			unique, filterable, sortable, localized, nullable,
			type, defaultValue, indexedDecimalPlaces
		);
		this.representative = representative;
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		boolean localized
	) {
		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			null, false, false, localized, false, false,
			type, null,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable AttributeUniquenessType unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue
	) {
		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			unique, filterable, sortable, localized, nullable, representative,
			type, defaultValue,
			0
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable AttributeUniquenessType unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			unique, filterable, sortable, localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable AttributeUniquenessType unique,
		boolean filterable,
		boolean sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new EntityAttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			unique, filterable, sortable, localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	@Override
	public String toString() {
		return "GlobalAttributeSchema{" +
			"name='" + getName() + '\'' +
			", unique=" + getUniquenessType() +
			", filterable=" + isFilterable() +
			", sortable=" + isSortable() +
			", localized=" + isLocalized() +
			", nullable=" + isNullable() +
			", representative=" + isRepresentative() +
			", type=" + getType() +
			", indexedDecimalPlaces=" + getIndexedDecimalPlaces() +
			'}';
	}

}
