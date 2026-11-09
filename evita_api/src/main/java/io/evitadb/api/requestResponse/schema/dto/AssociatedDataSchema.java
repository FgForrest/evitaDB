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

package io.evitadb.api.requestResponse.schema.dto;

import io.evitadb.api.requestResponse.mutation.conflict.ConflictResolutionOverride;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;
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
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Internal implementation of {@link AssociatedDataSchemaContract}.
 */
@Immutable
@ThreadSafe
@EqualsAndHashCode
public class AssociatedDataSchema implements Serializable, AssociatedDataSchemaContract {
	@Serial private static final long serialVersionUID = -995599294301442062L;

	@Getter @Nonnull private final String name;
	@Getter @Nonnull private final Map<NamingConvention, String> nameVariants;
	@Getter @Nullable private final String description;
	@Getter @Nullable private final String deprecationNotice;
	@Getter @Nonnull private final Class<? extends Serializable> type;
	@Getter @Nonnull private final Class<? extends Serializable> plainType;
	@Getter private final boolean localized;
	@Getter private final boolean nullable;
	/**
	 * Set of scopes where the associated data is searchable - the text of its values is analyzed into words and
	 * indexed in the fulltext index of that scope. See {@link AssociatedDataSchemaContract#isSearchable()}.
	 */
	@Getter @Nonnull private final Set<Scope> searchableInScopes;
	/**
	 * The EvitaEL expression selecting the text of a complex data object value, see
	 * {@link AssociatedDataSchemaContract#getTextExpression()}.
	 */
	@Getter @Nullable private final Expression textExpression;
	@Getter @Nonnull private final ConflictResolutionOverride conflictResolutionOverride;
	/**
	 * The name of the fulltext analyzer that turns the text into words while the associated data is searchable. See
	 * {@link AssociatedDataSchemaContract#getAnalyzer()}.
	 */
	@Getter @Nonnull private final String analyzer;
	/**
	 * The length pivot of the associated data's fulltext field. See
	 * {@link AssociatedDataSchemaContract#getLengthPivot()}.
	 */
	@Getter private final int lengthPivot;

	/**
	 * The fulltext ranking weight of the associated data. Unlike the analyzer and the length pivot it is not part of
	 * the persisted fulltext field definition, so changing it never rebuilds the field. See
	 * {@link AssociatedDataSchemaContract#getSearchWeight()}.
	 */
	@Getter private final float searchWeight;

	/**
	 * Reconstructs a schema, including its fulltext ranking settings, for internal use only. Not for client code.
	 *
	 * @param name schema name
	 * @param description human-readable description, or `null`
	 * @param deprecationNotice deprecation notice, or `null`
	 * @param type stored value type
	 * @param localized whether values are localized
	 * @param nullable whether values may be absent
	 * @param searchableInScopes scopes with fulltext indexing enabled
	 * @param textExpression complex data text selection expression, or `null`
	 * @param conflictResolutionOverride per-item conflict resolution override
	 * @param analyzer fulltext analyzer name
	 * @param lengthPivot positive fulltext length pivot
	 * @param searchWeight finite, non-negative ranking weight; zero contributes no ranking strength
	 * @return immutable schema carrying all supplied settings
	 */
	public static AssociatedDataSchema _internalBuild(
		@Nonnull String name,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable,
		@Nullable Scope[] searchableInScopes,
		@Nullable Expression textExpression,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride,
		@Nonnull String analyzer,
		int lengthPivot,
		float searchWeight
	) {
		return new AssociatedDataSchema(
			name, NamingConvention.generate(name), description, deprecationNotice, type, localized, nullable,
			ArrayUtils.toEnumSet(Scope.class, searchableInScopes), textExpression, conflictResolutionOverride,
			analyzer, lengthPivot, searchWeight
		);
	}

	/**
	 * Reconstructs a schema, including its fulltext ranking settings, for internal use only. Not for client code.
	 *
	 * @param name schema name
	 * @param nameVariants names in supported naming conventions
	 * @param description human-readable description, or `null`
	 * @param deprecationNotice deprecation notice, or `null`
	 * @param type stored value type
	 * @param localized whether values are localized
	 * @param nullable whether values may be absent
	 * @param searchableInScopes scopes with fulltext indexing enabled
	 * @param textExpression complex data text selection expression, or `null`
	 * @param conflictResolutionOverride per-item conflict resolution override
	 * @param analyzer fulltext analyzer name
	 * @param lengthPivot positive fulltext length pivot
	 * @param searchWeight finite, non-negative ranking weight; zero contributes no ranking strength
	 * @return immutable schema carrying all supplied settings
	 */
	public static AssociatedDataSchema _internalBuild(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable,
		@Nullable Set<Scope> searchableInScopes,
		@Nullable Expression textExpression,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride,
		@Nonnull String analyzer,
		int lengthPivot,
		float searchWeight
	) {
		return new AssociatedDataSchema(
			name, nameVariants,
			description, deprecationNotice, type, localized, nullable, searchableInScopes, textExpression,
			conflictResolutionOverride, analyzer, lengthPivot, searchWeight
		);
	}

	/**
	 * This method is for internal purposes only. It could be used for reconstruction of AssociatedDataSchema from
	 * different package than current, but still internal code of the Evita ecosystems.
	 *
	 * Do not use this method from in the client code!
	 *
	 * @param name schema name
	 * @param type stored value type
	 * @param conflictResolutionOverride per-item conflict resolution override
	 * @return new schema with the default analyzer, length pivot and ranking weight
	 */
	public static AssociatedDataSchemaContract _internalBuild(
		@Nonnull String name,
		@Nonnull Class<? extends Serializable> type,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride
	) {
		return new AssociatedDataSchema(
			name, NamingConvention.generate(name), null, null, type, false, false, null, null,
			conflictResolutionOverride,
			FulltextAnalyzerSchemaContract.TEXT, AttributeSchemaContract.DEFAULT_LENGTH_PIVOT,
			AttributeSchemaContract.DEFAULT_SEARCH_WEIGHT
		);
	}

	/**
	 * Creates immutable associated data settings, including the fulltext ranking weight.
	 *
	 * @param name schema name
	 * @param nameVariants names in supported naming conventions
	 * @param description human-readable description, or `null`
	 * @param deprecationNotice deprecation notice, or `null`
	 * @param type stored value type
	 * @param localized whether values are localized
	 * @param nullable whether values may be absent
	 * @param searchableInScopes scopes with fulltext indexing enabled
	 * @param textExpression complex data text selection expression, or `null`
	 * @param conflictResolutionOverride per-item conflict resolution override
	 * @param analyzer fulltext analyzer name
	 * @param lengthPivot positive fulltext length pivot
	 * @param searchWeight finite, non-negative ranking weight; zero contributes no ranking strength
	 */
	private AssociatedDataSchema(
		@Nonnull String name,
		@Nonnull Map<NamingConvention, String> nameVariants,
		@Nullable String description,
		@Nullable String deprecationNotice,
		@Nonnull Class<? extends Serializable> type,
		boolean localized,
		boolean nullable,
		@Nullable Set<Scope> searchableInScopes,
		@Nullable Expression textExpression,
		@Nonnull ConflictResolutionOverride conflictResolutionOverride,
		@Nonnull String analyzer,
		int lengthPivot,
		float searchWeight
	) {
		Assert.notNull(analyzer, "The fulltext analyzer of associated data `" + name + "` must not be null!");
		this.name = name;
		this.nameVariants = nameVariants;
		this.description = description;
		this.deprecationNotice = deprecationNotice;
		this.type = EvitaDataTypes.isSupportedTypeOrItsArray(type) ?
			EvitaDataTypes.toWrappedForm(type) :
			ComplexDataObject.class;
		//noinspection unchecked
		this.plainType = (Class<? extends Serializable>) (this.type.isArray() ? this.type.getComponentType() : this.type);
		this.localized = localized;
		this.nullable = nullable;
		this.searchableInScopes = CollectionUtils.toUnmodifiableSet(
			searchableInScopes == null || searchableInScopes.isEmpty() ?
				EnumSet.noneOf(Scope.class) : EnumSet.copyOf(searchableInScopes)
		);
		this.textExpression = textExpression;
		this.conflictResolutionOverride = conflictResolutionOverride;
		this.analyzer = analyzer;
		this.lengthPivot = lengthPivot;
		this.searchWeight = AttributeSchemaContract.normalizeSearchWeight(searchWeight);
	}

	@Override
	@Nonnull
	public String getNameVariant(@Nonnull NamingConvention namingConvention) {
		return this.nameVariants.get(namingConvention);
	}

	@Override
	public boolean isSearchableInScope(@Nonnull Scope scope) {
		return this.searchableInScopes.contains(scope);
	}

	@Override
	public String toString() {
		return "Associated data `" + this.name + "`: " +
			"description=" + this.description +
			", deprecationNotice=" + this.deprecationNotice +
			", type=" + this.type.getName() +
			", localized=" + this.localized +
			", nullable=" + this.nullable +
			", searchableInScopes=" + this.searchableInScopes +
			", textExpression=" + this.textExpression +
			", conflictResolutionOverride=" + this.conflictResolutionOverride +
			", analyzer=" + this.analyzer +
			", lengthPivot=" + this.lengthPivot +
			", searchWeight=" + this.searchWeight;
	}

}
