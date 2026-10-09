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

import io.evitadb.api.query.require.AssociatedDataContent;
import io.evitadb.api.requestResponse.data.structure.AssociatedData;
import io.evitadb.api.requestResponse.data.structure.Entity;
import io.evitadb.api.query.expression.visitor.TextExpressionChecker;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.Scope;
import io.evitadb.dataType.data.ComplexDataObjectConverter;
import io.evitadb.dataType.expression.Expression;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * This is the definition object for {@link AssociatedData} that is stored along with
 * {@link Entity}. Definition objects allow to describe the structure of the entity type so that
 * in any time everyone can consult complete structure of the entity type. Definition object is similar to Java reflection
 * process where you can also at any moment see which fields and methods are available for the class.
 *
 * Associated data carry additional data entries that are never used for filtering / sorting but may be needed to be fetched
 * along with entity in order to present data to the target consumer (i.e. user / API / bot). Associated data may be stored
 * in slower storage and may contain wide range of data types - from small ones (i.e. numbers, strings, dates) up to large
 * binary arrays representing entire files (i.e. pictures, documents). The one index associated data may take part in is
 * the fulltext one - see {@link #isSearchable()}.
 *
 * The search query must contain specific {@link AssociatedDataContent} requirement in order associated data are fetched
 * along with the entity. Associated data are stored and fetched separately by their name.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AssociatedDataSchemaContract extends NamedSchemaWithDeprecationContract, ConflictResolutionOverrideAwareSchemaContract {

	/**
	 * Contains the data type of the entity. Must be one of {@link EvitaDataTypes#getSupportedDataTypes()} types or may
	 * represent complex type - which is POJO that can be automatically ({@link ComplexDataObjectConverter}) converted
	 * to the set of basic types.
	 */
	@Nonnull
	Class<? extends Serializable> getType();

	/**
	 * Returns attribute type that represents non-array type class. I.e. method just unwraps array types to plain ones.
	 */
	@Nonnull
	Class<? extends Serializable> getPlainType();

	/**
	 * Localized associated data has to be ALWAYS used in connection with specific {@link Locale}. In other
	 * words - it cannot be stored unless associated locale is also provided.
	 */
	boolean isLocalized();

	/**
	 * When associated data is nullable, its values may be missing in the entities. Otherwise, the system will enforce
	 * non-null checks upon upserting of the entity.
	 */
	boolean isNullable();

	/**
	 * Name of the variable the stored value is bound to in the {@link #getTextExpression() text expression}.
	 */
	String TEXT_EXPRESSION_VARIABLE = "value";

	/**
	 * When associated data is searchable, the text its values contain is analyzed into words and indexed in the
	 * fulltext index of the entity collection, one index per locale, so that entities can be found and ranked by those
	 * words - exactly as for a searchable attribute ({@link AttributeSchemaContract#isSearchable()}). Each searchable
	 * associated data occupies (memory/disk) space in that index, and maintaining it adds work to every write.
	 *
	 * Only **localized** associated data may be searchable, because the words of a value can only be analyzed in the
	 * language they are written in, and only of these types:
	 *
	 * - `String` and `String[]` - the value is the text, each element of an array is analyzed on its own;
	 * - {@link ComplexDataObject} (any POJO type) - the text is selected by the {@link #getTextExpression() text
	 *   expression}, which is mandatory for this type and forbidden for the other two.
	 *
	 * The rules are reported by {@link #validate()} on the assembled schema. Markup in the text is stripped by the
	 * index analyzers, so an HTML body can be searchable as it is stored.
	 *
	 * **Over stored data**, declaring associated data searchable is never refused, but values stored before the
	 * declaration are not indexed retroactively - stored data is not reindexed yet - so a search returns incomplete
	 * results until such a value is written again. Withdrawing searchability takes effect lazily, at the next write of
	 * the associated data in the scope and locale; a later re-declaration starts from an empty field. A changed text
	 * expression applies to a field built anew only: until stored data is reindexed, the field keeps selecting text the
	 * way it did when it was built. Results are only ever incomplete, never phantom.
	 *
	 * This method returns true only if the associated data is searchable in the default (i.e. {@link Scope#LIVE})
	 * scope.
	 *
	 * @return true if the associated data is searchable in the default (i.e. {@link Scope#LIVE}) scope
	 */
	default boolean isSearchable() {
		return isSearchableInScope(Scope.DEFAULT_SCOPE);
	}

	/**
	 * Returns true if the associated data is searchable in at least one scope - see {@link #isSearchable()} for what
	 * searchability means.
	 *
	 * @return true if the associated data is searchable in any scope
	 */
	default boolean isSearchableInAnyScope() {
		return Arrays.stream(Scope.values()).anyMatch(this::isSearchableInScope);
	}

	/**
	 * Returns true if the associated data is searchable in the given scope - see {@link #isSearchable()} for what
	 * searchability means.
	 *
	 * @param scope to check the associated data is searchable in
	 * @return true if the associated data is searchable in particular scope
	 */
	boolean isSearchableInScope(@Nonnull Scope scope);

	/**
	 * Retrieves the set of scopes in which the associated data is searchable - see {@link #isSearchable()} for what
	 * searchability means.
	 *
	 * @return set of scopes in which the associated data is searchable, empty when it is searchable nowhere
	 */
	@Nonnull
	Set<Scope> getSearchableInScopes();

	/**
	 * Returns the EvitaEL expression selecting the text of a {@link ComplexDataObject} value, with the stored value
	 * bound to `$value` (see {@link #TEXT_EXPRESSION_VARIABLE}), e.g. `$value.title` or `$value.blocks.*[$.text]`.
	 * The expression is expected to yield a `String`, an array or list of strings, or nested lists of them; a missing
	 * property and a `null` contribute no text. A written value for which the expression yields anything else, or
	 * over which it cannot be evaluated, fails the write - a plain spread over a missing collection does, so use `?.*`
	 * for an optional one. Values the engine re-reads on its own (a scope change, a removal) never fail: what does not
	 * fit simply contributes no text. The expression applies to every scope the associated data is searchable in.
	 *
	 * @return the text expression, or `null` when none is set
	 */
	@Nullable
	Expression getTextExpression();

	/**
	 * Collects the schema-consistency errors of this associated data, one message per problem, **without throwing** -
	 * the counterpart of {@link AttributeSchemaContract#validate()}, gathered by the entity schema validation together
	 * with the errors of its attributes and references.
	 *
	 * **This runs on an assembled schema only**, so that `searchable()`, `localized()`, a type change and the text
	 * expression may be declared in any order and only their outcome has to make sense.
	 *
	 * @return one message per consistency problem, empty when the associated data is valid
	 */
	@Nonnull
	default Stream<String> validate() {
		if (!isSearchableInAnyScope()) {
			return Stream.empty();
		}
		Stream<String> errors = Stream.empty();
		final boolean complexDataObject = ComplexDataObject.class.equals(getPlainType());
		if (!complexDataObject && !String.class.equals(getPlainType())) {
			errors = Stream.concat(
				errors,
				Stream.of(
					"Associated data `" + getName() + "` is searchable, but its type is `" + getType().getName() +
						"`! Only associated data of type `String`, `String[]` or a complex data object can be " +
						"searchable."
				)
			);
		}
		if (!isLocalized()) {
			errors = Stream.concat(
				errors,
				Stream.of(
					"Associated data `" + getName() + "` is searchable, but it is not localized! Searchable " +
						"associated data must be localized, because its words are analyzed in the language of the " +
						"locale its value is written in."
				)
			);
		}
		final Expression textExpression = getTextExpression();
		if (complexDataObject && textExpression == null) {
			errors = Stream.concat(
				errors,
				Stream.of(
					"Associated data `" + getName() + "` is a searchable complex data object, but it has no text " +
						"expression! Declare which parts of the object hold the text, e.g. `$value.title` or " +
						"`$value.blocks.*[$.text]`."
				)
			);
		} else if (!complexDataObject && textExpression != null) {
			errors = Stream.concat(
				errors,
				Stream.of(
					"Associated data `" + getName() + "` declares a text expression, but its type is `" +
						getType().getName() + "`! A text expression selects text from a complex data object only - " +
						"the value of a `String` or `String[]` is the text itself."
				)
			);
		}
		if (textExpression != null) {
			errors = Stream.concat(
				errors,
				TextExpressionChecker.findProblems(textExpression, TEXT_EXPRESSION_VARIABLE)
					.stream()
					.map(problem -> "Text expression of associated data `" + getName() + "` is invalid: " + problem)
			);
		}
		return errors;
	}

}
