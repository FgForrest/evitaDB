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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.schema.EntityAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal implementation of {@link EntityAttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(callSuper = true)
public final class EntityAttributeSchema extends AttributeSchema implements EntityAttributeSchemaContract {
	@Serial private static final long serialVersionUID = 8168305590483159082L;

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		boolean localized
	) {
		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			null, null, null,
			localized, false, false,
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
	@Nonnull
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
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
	@Nonnull
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new EntityAttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
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
	@Nonnull
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
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
			uniqueInScopes,
			filterableInScopes,
			sortableInScopes,
			localized, nullable, representative,
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
	@Nonnull
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
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
			uniqueInScopes,
			filterableInScopes,
			sortableInScopes,
			localized, nullable, representative,
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
	@Nonnull
	public static <T extends Serializable> EntityAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] uniqueInScopes,
		@Nullable Scope[] filterableInScopes,
		@Nullable Scope[] sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(uniqueInScopes);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterableInScopes);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortableInScopes);

		return new EntityAttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			theUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	<T extends Serializable> EntityAttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Set<Scope> filterableInScopes,
		@Nullable Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		super(
			name, nameVariants, description, deprecationNotice,
			uniqueInScopes, filterableInScopes, sortableInScopes,
			localized, nullable, representative,
			type, defaultValue, indexedDecimalPlaces
		);
	}

	@Override
	public String toString() {
		return "EntityAttributeSchema{" +
			"name='" + this.name + '\'' + (this.deprecationNotice == null ? "" : " (deprecated)") +
			", unique=(" + (this.uniquenessTypeInScopes.entrySet().stream().map(it -> it.getKey() + ": " + it.getValue().name())) + ")" +
			", filterable=" + (this.filterableInScopes.isEmpty() ? "no" : "(in scopes: " + this.filterableInScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ")") +
			", sortable=" + (this.sortableInScopes.isEmpty() ? "no" : "(in scopes: " + this.sortableInScopes.stream().map(Enum::name).collect(Collectors.joining(", ")) + ")") +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", representative=" + this.representative +
			", type=" + this.type +
			", indexedDecimalPlaces=" + this.indexedDecimalPlaces +
			", defaultValue=" + this.defaultValue +
			'}';
	}

}
