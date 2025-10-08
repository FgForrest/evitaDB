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

import io.evitadb.api.requestResponse.schema.GlobalAttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedAttributeUniquenessType;
import io.evitadb.api.requestResponse.schema.mutation.attribute.ScopedGlobalAttributeUniquenessType;
import io.evitadb.dataType.Scope;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.CollectionUtils;
import io.evitadb.utils.NamingConvention;
import lombok.EqualsAndHashCode;
import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Internal implementation of {@link GlobalAttributeSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode(callSuper = true)
public final class GlobalAttributeSchema extends AttributeSchema implements GlobalAttributeSchemaContract {
	@Serial private static final long serialVersionUID = -6027390261318420826L;
	/**
	 * A mapping of {@link Scope} to their corresponding {@link GlobalAttributeUniquenessType}.
	 * This map specifies the global uniqueness constraints for attributes within given scopes.
	 * Each entry in the map defines the type of global uniqueness enforced for a particular scope.
	 */
	@Getter private final Map<Scope, GlobalAttributeUniquenessType> globalUniquenessTypeInScopes;

	/**
	 * Converts an array of ScopedGlobalAttributeUniquenessType objects into an EnumMap linking Scope to GlobalAttributeUniquenessType.
	 * If the input array is null, it initializes the map with a default value of Scope.DEFAULT_SCOPE mapped to GlobalAttributeUniquenessType.NOT_UNIQUE.
	 *
	 * @param uniqueInScopes An array of ScopedAttributeUniquenessType to be converted. Can be null.
	 * @return An EnumMap where each Scope is associated with its corresponding AttributeUniquenessType.
	 */
	@Nonnull
	public static EnumMap<Scope, GlobalAttributeUniquenessType> toGlobalUniquenessEnumMap(@Nullable ScopedGlobalAttributeUniquenessType[] uniqueInScopes) {
		final EnumMap<Scope, GlobalAttributeUniquenessType> theUniquenessType = new EnumMap<>(Scope.class);
		if (uniqueInScopes != null) {
			for (ScopedGlobalAttributeUniquenessType uniqueInScope : uniqueInScopes) {
				theUniquenessType.put(uniqueInScope.scope(), uniqueInScope.uniquenessType());
			}
		} else {
			theUniquenessType.put(Scope.DEFAULT_SCOPE, GlobalAttributeUniquenessType.NOT_UNIQUE);
		}
		return theUniquenessType;
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of GlobalAttributeSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 */
	@Nonnull
	public static GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		boolean localized
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(null);
		final EnumMap<Scope, GlobalAttributeUniquenessType> theGlobalUniquenessType = toGlobalUniquenessEnumMap(null);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, null);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, null);
		return new GlobalAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			theUniquenessType,
			theGlobalUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
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
	public static <T extends Serializable> GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable ScopedAttributeUniquenessType[] unique,
		@Nullable ScopedGlobalAttributeUniquenessType[] uniqueGlobally,
		@Nullable Scope[] filterable,
		@Nullable Scope[] sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(unique);
		final EnumMap<Scope, GlobalAttributeUniquenessType> theGlobalUniquenessType = toGlobalUniquenessEnumMap(uniqueGlobally);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterable);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortable);
		return new GlobalAttributeSchema(
			name, NamingConvention.generate(name),
			null, null,
			theUniquenessType,
			theGlobalUniquenessType,
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
	public static <T extends Serializable> GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] unique,
		@Nullable ScopedGlobalAttributeUniquenessType[] uniqueGlobally,
		@Nullable Scope[] filterable,
		@Nullable Scope[] sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(unique);
		final EnumMap<Scope, GlobalAttributeUniquenessType> theGlobalUniquenessType = toGlobalUniquenessEnumMap(uniqueGlobally);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterable);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortable);
		return new GlobalAttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			theUniquenessType,
			theGlobalUniquenessType,
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
	public static <T extends Serializable> GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Map<Scope, GlobalAttributeUniquenessType> globalUniquenessTypeInScopes,
		@Nonnull Set<Scope> filterableInScopes,
		@Nonnull Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new GlobalAttributeSchema(
			name, NamingConvention.generate(name),
			description, deprecationNotice,
			uniqueInScopes,
			globalUniquenessTypeInScopes,
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
	public static <T extends Serializable> GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Map<Scope, GlobalAttributeUniquenessType> globalUniquenessTypeInScopes,
		@Nonnull Set<Scope> filterableInScopes,
		@Nonnull Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		return new GlobalAttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			uniqueInScopes,
			globalUniquenessTypeInScopes,
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
	public static <T extends Serializable> GlobalAttributeSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable ScopedAttributeUniquenessType[] unique,
		@Nullable ScopedGlobalAttributeUniquenessType[] uniqueGlobally,
		@Nullable Scope[] filterable,
		@Nullable Scope[] sortable,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		final EnumMap<Scope, AttributeUniquenessType> theUniquenessType = toUniquenessEnumMap(unique);
		final EnumMap<Scope, GlobalAttributeUniquenessType> theGlobalUniquenessType = toGlobalUniquenessEnumMap(uniqueGlobally);
		final EnumSet<Scope> theFilterableInScopes = ArrayUtils.toEnumSet(Scope.class, filterable);
		final EnumSet<Scope> theSortableInScopes = ArrayUtils.toEnumSet(Scope.class, sortable);
		return new GlobalAttributeSchema(
			name, nameVariants,
			description, deprecationNotice,
			theUniquenessType,
			theGlobalUniquenessType,
			theFilterableInScopes,
			theSortableInScopes,
			localized, nullable, representative,
			type, defaultValue,
			indexedDecimalPlaces
		);
	}

	<T extends Serializable> GlobalAttributeSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Map<Scope, GlobalAttributeUniquenessType> globalUniquenessTypeInScopes,
		@Nonnull Set<Scope> filterableInScopes,
		@Nonnull Set<Scope> sortableInScopes,
		boolean localized,
		boolean nullable,
		boolean representative,
		@Nonnull Class<T> type,
		@Nullable T defaultValue,
		int indexedDecimalPlaces
	) {
		super(
			name, nameVariants, description, deprecationNotice,
			verifyAndAlterUniquenessTypes(uniqueInScopes, globalUniquenessTypeInScopes),
			filterableInScopes, sortableInScopes, localized, nullable, representative,
			type, defaultValue, indexedDecimalPlaces
		);
		if (globalUniquenessTypeInScopes == null || globalUniquenessTypeInScopes.isEmpty()) {
			final EnumMap<Scope, GlobalAttributeUniquenessType> theMap = new EnumMap<>(Scope.class);
			this.globalUniquenessTypeInScopes = Collections.unmodifiableMap(theMap);
			theMap.put(Scope.DEFAULT_SCOPE, GlobalAttributeUniquenessType.NOT_UNIQUE);
		} else {
			this.globalUniquenessTypeInScopes = CollectionUtils.toUnmodifiableMap(globalUniquenessTypeInScopes);
		}
	}

	@Nullable
	private static Map<Scope, AttributeUniquenessType> verifyAndAlterUniquenessTypes(
		@Nullable Map<Scope, AttributeUniquenessType> uniqueInScopes,
		@Nullable Map<Scope, GlobalAttributeUniquenessType> globalUniquenessTypeInScopes
	) {
		if ((uniqueInScopes == null || uniqueInScopes.isEmpty()) && (globalUniquenessTypeInScopes == null || globalUniquenessTypeInScopes.isEmpty())) {
			return null;
		} else {
			final EnumMap<Scope, AttributeUniquenessType> theMap = new EnumMap<>(Scope.class);
			for (Scope scope : Scope.values()) {
				final AttributeUniquenessType attributeUniquenessType = uniqueInScopes == null ?
					null : uniqueInScopes.get(scope);
				final GlobalAttributeUniquenessType globalAttributeUniquenessType = globalUniquenessTypeInScopes == null ?
					null : globalUniquenessTypeInScopes.get(scope);
				if (attributeUniquenessType != null && (globalAttributeUniquenessType == null || globalAttributeUniquenessType == GlobalAttributeUniquenessType.NOT_UNIQUE)) {
					theMap.put(scope, attributeUniquenessType);
				} else if (globalAttributeUniquenessType != null) {
					// global attribute setting has always precedence
					switch (globalAttributeUniquenessType) {
						case UNIQUE_WITHIN_CATALOG ->  theMap.put(scope, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION);
						case UNIQUE_WITHIN_CATALOG_LOCALE -> theMap.put(scope, AttributeUniquenessType.UNIQUE_WITHIN_COLLECTION_LOCALE);
						case NOT_UNIQUE -> theMap.put(scope, AttributeUniquenessType.NOT_UNIQUE);
					}
				}
			}
			return theMap;
		}
	}

	@Override
	public boolean isUnique() {
		return super.isUnique() || isUniqueGlobally();
	}

	@Override
	public boolean isUniqueInAnyScope() {
		return super.isUniqueInAnyScope() || isUniqueGloballyInAnyScope();
	}

	@Override
	public boolean isUniqueWithinLocale() {
		return super.isUniqueWithinLocale() || isUniqueGloballyWithinLocale();
	}

	@Override
	public boolean isUniqueWithinLocaleInAnyScope() {
		return super.isUniqueWithinLocaleInAnyScope() || isUniqueGloballyWithinLocaleInAnyScope();
	}

	@Override
	public boolean isUniqueGloballyInScope(@Nonnull Scope scope) {
		final GlobalAttributeUniquenessType globalAttributeUniquenessType = this.globalUniquenessTypeInScopes.get(scope);
		return globalAttributeUniquenessType != null && globalAttributeUniquenessType != GlobalAttributeUniquenessType.NOT_UNIQUE;
	}

	@Override
	public boolean isUniqueGloballyWithinLocaleInScope(@Nonnull Scope scope) {
		return this.globalUniquenessTypeInScopes.get(scope) == GlobalAttributeUniquenessType.UNIQUE_WITHIN_CATALOG_LOCALE;
	}

	@Nonnull
	@Override
	public GlobalAttributeUniquenessType getGlobalUniquenessType(@Nonnull Scope scope) {
		return Optional.ofNullable(this.globalUniquenessTypeInScopes.get(scope)).orElse(GlobalAttributeUniquenessType.NOT_UNIQUE);
	}

	@Override
	public String toString() {
		return "GlobalAttributeSchema{" +
			"name='" + this.name + '\'' + (this.deprecationNotice == null ? "" : " (deprecated)") +
			", unique=(" + (this.uniquenessTypeInScopes.entrySet().stream().map(it -> it.getKey() + ": " + it.getValue().name())) + ")" +
			", uniqueGlobally=(" + (this.globalUniquenessTypeInScopes.entrySet().stream().map(it -> it.getKey() + ": " + it.getValue().name())) + ")" +
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
