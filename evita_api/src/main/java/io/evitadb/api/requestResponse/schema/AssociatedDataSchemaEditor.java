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

import io.evitadb.dataType.Scope;
import io.evitadb.dataType.expression.Expression;

import javax.annotation.Nonnull;
import java.util.function.BooleanSupplier;

/**
 * Interface follows the <a href="https://en.wikipedia.org/wiki/Builder_pattern">builder pattern</a> allowing to alter
 * the data that are available on the read-only {@link AssociatedDataSchemaContract} interface.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AssociatedDataSchemaEditor extends
	AssociatedDataSchemaContract, NamedSchemaWithDeprecationEditor<AssociatedDataSchemaEditor>,
	ConflictResolutionOverrideAwareSchemaEditor<AssociatedDataSchemaEditor> {

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor localized();

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link java.util.Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 *
	 * @param decider returns true when associated data should be localized
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor localized(@Nonnull BooleanSupplier decider);

	/**
	 * When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor nullable();

	/**
	 * When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 *
	 * @param decider returns true when associated data should be nullable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor nullable(@Nonnull BooleanSupplier decider);

	/**
	 * Makes the associated data searchable: the text of its values is analyzed into words and indexed in the fulltext
	 * index of the entity collection, one index per locale. The text expression already set is kept - see
	 * {@link #searchable(Expression)} for a complex data object.
	 *
	 * Only **localized** associated data of type `String`, `String[]` or a complex data object (with a text expression)
	 * may be searchable; like the other cross-field rules, this is checked on the assembled schema, so the order of
	 * `searchable()`, `localized()` and the type does not matter. **Over stored data** the declaration is never
	 * refused, but values stored before it are not indexed retroactively - each becomes searchable when it is written
	 * again. See {@link AssociatedDataSchemaContract#isSearchable()} for the complete contract.
	 *
	 * This method makes the associated data searchable only in the {@link Scope#DEFAULT_SCOPE} scope, archived
	 * entities will not be searchable by it unless explicitly set via {@link #searchableInScope(Scope...)}.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default AssociatedDataSchemaEditor searchable() {
		return searchableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Makes the associated data searchable in the {@link Scope#DEFAULT_SCOPE} scope and sets the EvitaEL expression
	 * selecting the text of its complex data object values, with the stored value bound to `$value` - for example
	 * `$value.title` or `$value.blocks.*[$.text]`. See {@link AssociatedDataSchemaContract#getTextExpression()}.
	 *
	 * @param textExpression the expression selecting the text of a value
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default AssociatedDataSchemaEditor searchable(@Nonnull Expression textExpression) {
		return searchableInScope(textExpression, Scope.DEFAULT_SCOPE);
	}

	/**
	 * Makes the associated data searchable in exactly the specified scope(s), keeping the text expression already
	 * set - see {@link #searchable()} for what searchability means. The method is a full statement of the scopes: a
	 * scope not listed here ends up not searchable.
	 *
	 * @param inScope one or more scopes in which the associated data should be searchable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor searchableInScope(@Nonnull Scope... inScope);

	/**
	 * Makes the associated data searchable in exactly the specified scope(s) and sets the text expression selecting
	 * the text of its complex data object values - see {@link #searchable(Expression)}. The expression applies to
	 * every listed scope.
	 *
	 * @param textExpression the expression selecting the text of a value
	 * @param inScope        one or more scopes in which the associated data should be searchable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor searchableInScope(@Nonnull Expression textExpression, @Nonnull Scope... inScope);

	/**
	 * Makes the associated data searchable in the {@link Scope#DEFAULT_SCOPE} scope when the decider says so, and
	 * not searchable in any scope when it does not - see {@link #searchable()} for what searchability means.
	 *
	 * @param decider returns true when the associated data should be searchable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default AssociatedDataSchemaEditor searchable(@Nonnull BooleanSupplier decider) {
		return decider.getAsBoolean() ? searchable() : nonSearchable();
	}

	/**
	 * Makes the associated data not searchable in any scope, and drops its text expression. The withdrawal takes
	 * effect lazily - at the next write of the associated data in each scope and locale - and the space its values
	 * took in the fulltext index is not reclaimed; declaring it searchable again starts from an empty field.
	 *
	 * @return builder to continue with configuration
	 */
	@Nonnull
	default AssociatedDataSchemaEditor nonSearchable() {
		return nonSearchableInScope(Scope.values());
	}

	/**
	 * Makes the associated data not searchable in the specified scope(s), leaving the other scopes as they are. When
	 * no searchable scope remains, the text expression is dropped too. The withdrawal takes effect lazily - see
	 * {@link #nonSearchable()}.
	 *
	 * @param inScope one or more scopes in which the associated data should not be searchable
	 * @return builder to continue with configuration
	 */
	@Nonnull
	AssociatedDataSchemaEditor nonSearchableInScope(@Nonnull Scope... inScope);

}
