/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.index.fulltext;

import io.evitadb.api.query.expression.evaluate.SingleVariableEvaluationContext;
import io.evitadb.api.query.expression.object.accessor.common.DataItemSupport;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.ComplexDataObject;
import io.evitadb.dataType.data.DataItem;
import io.evitadb.dataType.data.DataItemArray;
import io.evitadb.dataType.expression.Expression;
import io.evitadb.exception.ExpressionEvaluationException;
import io.evitadb.utils.CollectionUtils;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a stored associated data value into the text a searchable fulltext field indexes: a set of distinct
 * strings, each analyzed on its own and summed into the field's one entry for the entity (see {@link FulltextIndex},
 * "Multi-valued fields").
 *
 * The extraction **dispatches on the runtime type of the value**, not on the schema type, so that a value stored before
 * a type change extracts identically when it is added and when it is removed:
 *
 * - a `String` is one text, a `String[]` its non-null elements;
 * - a {@link ComplexDataObject} is navigated by the text expression, its root bound to `$value`
 *   ({@link AssociatedDataSchemaContract#TEXT_EXPRESSION_VARIABLE}); without an expression it holds no text;
 * - anything else holds no text.
 *
 * The expression result contributes every `String` it holds - directly, as elements of a list, an array or an array
 * node of the complex data object (its elements unwrapped), or as the values of a map (the result of spreading a map),
 * nested collections flattened. A map node of the complex data object is an object, not a collection, so it holds no
 * text - `.*[$]` spreads its values. Nulls - a missing path, a missing property of one element of a spread - are
 * skipped in both modes.
 *
 * **Two modes, picked by who can act** ({@link ExtractionMode}):
 *
 * - {@link ExtractionMode#STRICT} for the value a client's upsert carries: a non-string leaf, or an expression that
 *   throws over the value, fails the write with an {@link ExpressionEvaluationException} naming the entity type, the
 *   associated data, the expression and the offending type - the client can fix the value or the expression;
 * - {@link ExtractionMode#LENIENT} for every value the engine extracts on its own - the old value a removal
 *   re-extracts, a scope change, a reindex: a non-string leaf is skipped and a throwing expression selects nothing,
 *   reported to the log once per (entity type, associated data) and kind, because nobody could act on a failure there.
 *
 * **Removal re-extracts exactly what the add produced.** A value that passed the strict mode has only string and null
 * leaves and an expression that evaluates over it, so the lenient mode extracts the very same texts from it; a value
 * added leniently is removed leniently, deterministically the same way.
 *
 * The leaves are collected as a **distinct set**, the rule searchable attributes follow too, so that an add and a
 * remove of the same value always agree.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Slf4j
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SearchableTextExtractor {

	/**
	 * The (entity type, associated data, kind) triples already reported, so that a problem is logged once per item
	 * rather than once per written value.
	 */
	private static final Set<String> REPORTED_PROBLEMS = ConcurrentHashMap.newKeySet(16);
	/**
	 * The start of the message EvitaEL reports a spread over a `null` operand with - the case `?.*` exists for.
	 */
	private static final String NULL_SPREAD_OPERAND = "Cannot spread operand, operand is null";

	/**
	 * How strictly a value that does not fit the expression is treated - see the class documentation.
	 */
	public enum ExtractionMode {

		/**
		 * The value a client's write carries: a non-string leaf or a throwing expression fails the write.
		 */
		STRICT,
		/**
		 * A value the engine extracts on its own: a non-string leaf is skipped, a throwing expression selects nothing.
		 */
		LENIENT

	}

	/**
	 * Extracts the distinct texts of a stored associated data value.
	 *
	 * @param value              the stored value
	 * @param textExpression     the expression selecting the text of a complex data object, or `null`
	 * @param entityType         the entity type, for the log
	 * @param associatedDataName the associated data, for the log
	 * @param mode               how strictly a value that does not fit the expression is treated
	 * @return the distinct texts in the order they were found, empty when the value holds none
	 * @throws ExpressionEvaluationException in the {@link ExtractionMode#STRICT} mode, when the expression throws over
	 *                                       the value or yields a leaf that is not a string
	 */
	@Nonnull
	public static Set<String> extract(
		@Nonnull Serializable value,
		@Nullable Expression textExpression,
		@Nonnull String entityType,
		@Nonnull String associatedDataName,
		@Nonnull ExtractionMode mode
	) {
		final Set<String> texts = CollectionUtils.createLinkedHashSet(4);
		if (value instanceof String text) {
			texts.add(text);
		} else if (value instanceof String[] array) {
			for (final String text : array) {
				if (text != null) {
					texts.add(text);
				}
			}
		} else if (value instanceof ComplexDataObject complexDataObject && textExpression != null) {
			extractFromComplexDataObject(
				complexDataObject, textExpression, entityType, associatedDataName, mode, texts
			);
		}
		return texts;
	}

	/**
	 * Adds the texts the expression selects from a complex data object to `texts` - see {@link #extract}.
	 *
	 * @param complexDataObject  the stored value
	 * @param textExpression     the expression selecting the text
	 * @param entityType         the entity type, for the log
	 * @param associatedDataName the associated data, for the log
	 * @param mode               how strictly a value that does not fit the expression is treated
	 * @param texts              the set collecting the texts
	 * @throws ExpressionEvaluationException in the {@link ExtractionMode#STRICT} mode, when the expression throws over
	 *                                       the value or yields a leaf that is not a string
	 */
	private static void extractFromComplexDataObject(
		@Nonnull ComplexDataObject complexDataObject,
		@Nonnull Expression textExpression,
		@Nonnull String entityType,
		@Nonnull String associatedDataName,
		@Nonnull ExtractionMode mode,
		@Nonnull Set<String> texts
	) {
		final Serializable root = DataItemSupport.unwrap(complexDataObject);
		if (root == null) {
			return;
		}
		final Serializable result;
		try {
			result = textExpression.compute(
				new SingleVariableEvaluationContext(AssociatedDataSchemaContract.TEXT_EXPRESSION_VARIABLE, root)
			);
		} catch (RuntimeException e) {
			// any failure of the evaluation counts - an engine-initiated path (removal, scope change) must never
			// fail on a value it did not write, and a client write gets one well-formed error instead of a raw one
			if (mode == ExtractionMode.STRICT) {
				// a plain spread over a missing collection throws in EvitaEL - say how to make it optional
				final String hint = e.getMessage() != null && e.getMessage().contains(NULL_SPREAD_OPERAND) ?
					" Use `?.*` for an optional collection." : "";
				throw new ExpressionEvaluationException(
					"The text expression `" + textExpression.toExpressionString() + "` of searchable " +
						"associated data `" + associatedDataName + "` of `" + entityType + "` cannot be " +
						"evaluated over the written value: " + e.getMessage() + hint,
					"The text expression of searchable associated data `" + associatedDataName +
						"` cannot be evaluated over the written value.",
					e
				);
			}
			// the value does not have the shape the expression expects - it selects no text, the write goes on
			reportOnce(
				entityType, associatedDataName, "evaluation",
				"The text expression `{}` of searchable associated data `{}` of `{}` cannot be evaluated " +
					"over a stored value ({}); such values contribute no searchable text.",
				textExpression.toExpressionString(), associatedDataName, entityType, e.getMessage()
			);
			return;
		}
		final Object offending = collect(result, texts);
		if (offending != null) {
			if (mode == ExtractionMode.STRICT) {
				throw new ExpressionEvaluationException(
					"The text expression `" + textExpression.toExpressionString() + "` of searchable associated " +
						"data `" + associatedDataName + "` of `" + entityType + "` yields a value of type `" +
						offending.getClass().getName() + "` for the written value - it may yield strings only " +
						"(missing values and nulls are skipped).",
					"The text expression of searchable associated data `" + associatedDataName +
						"` yields a value that is not a string."
				);
			}
			reportOnce(
				entityType, associatedDataName, "non-string",
				"The text expression `{}` of searchable associated data `{}` of `{}` yields values that are " +
					"not strings; they contribute no searchable text.",
				textExpression.toExpressionString(), associatedDataName, entityType
			);
		}
	}

	/**
	 * Adds every string an expression result holds to `target`, flattening lists, arrays, array nodes of a complex data
	 * object and map values. A map node of a complex data object is an object rather than a collection, so it is a
	 * leaf that is not a string.
	 *
	 * @param result the expression result
	 * @param target the set collecting the texts
	 * @return the first non-null leaf that is not a string - which was skipped - or `null` when there is none
	 */
	@Nullable
	private static Object collect(@Nullable Object result, @Nonnull Set<String> target) {
		if (result == null) {
			return null;
		} else if (result instanceof String text) {
			target.add(text);
			return null;
		} else if (result instanceof Collection<?> collection) {
			return collectAll(collection, target);
		} else if (result instanceof Object[] array) {
			return collectAll(Arrays.asList(array), target);
		} else if (result instanceof Map<?, ?> map) {
			return collectAll(map.values(), target);
		} else if (result instanceof DataItemArray array) {
			// an array node of a complex data object reads as an array of its unwrapped elements - a map node stays
			// an object, and an object is not text
			Object offending = null;
			for (final DataItem child : array.children()) {
				final Object childOffending = collect(DataItemSupport.unwrap(child), target);
				if (offending == null) {
					offending = childOffending;
				}
			}
			return offending;
		} else {
			return result;
		}
	}

	/**
	 * Adds every string the items hold to `target` - see {@link #collect(Object, Set)}.
	 *
	 * @param items  the items
	 * @param target the set collecting the texts
	 * @return the first non-null leaf that is not a string, or `null` when there is none
	 */
	@Nullable
	private static Object collectAll(@Nonnull Collection<?> items, @Nonnull Set<String> target) {
		Object offending = null;
		for (final Object item : items) {
			final Object itemOffending = collect(item, target);
			if (offending == null) {
				offending = itemOffending;
			}
		}
		return offending;
	}

	/**
	 * Logs a problem of an associated data's text extraction the first time it occurs for the item.
	 *
	 * @param entityType         the entity type
	 * @param associatedDataName the associated data
	 * @param kind               the kind of the problem
	 * @param message            the log message
	 * @param arguments          the arguments of the message placeholders
	 */
	private static void reportOnce(
		@Nonnull String entityType,
		@Nonnull String associatedDataName,
		@Nonnull String kind,
		@Nonnull String message,
		@Nonnull Object... arguments
	) {
		if (REPORTED_PROBLEMS.add(entityType + '\u0000' + associatedDataName + '\u0000' + kind)) {
			log.warn(message, arguments);
		}
	}

}
