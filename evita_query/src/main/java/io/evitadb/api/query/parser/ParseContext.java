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

package io.evitadb.api.query.parser;

import io.evitadb.api.query.parser.exception.EvitaSyntaxException;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;

/**
 * Context object shared in all parsing visitors. Contains metadata that should be accessible from any parsing
 * visitor, e.g. client arguments.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
public class ParseContext {

	@Getter
	@Setter
	@Nonnull
	private ParseMode mode = ParseMode.SAFE;

	@Nullable
	private final Queue<Object> positionalArguments;
	private int lastPositionalArgumentIndex = -1;

	@Nullable
	private final Map<String, Object> namedArguments;

	private ParseContext(@Nullable Queue<Object> positionalArguments, @Nullable Map<String, Object> namedArguments) {
		this.positionalArguments = positionalArguments;
		this.namedArguments = namedArguments;
	}

	/**
	 * Creates an empty parse context with no arguments.
	 */
	public ParseContext() {
		this((Queue<Object>) null, null);
	}

	/**
	 * Creates a parse context with positional arguments passed as varargs.
	 */
	public ParseContext(@Nonnull Object... positionalArguments) {
		this(new LinkedList<>(Arrays.asList(positionalArguments)), null);
	}

	/**
	 * Creates a parse context with positional arguments passed as a list.
	 */
	public ParseContext(@Nonnull List<Object> positionalArguments) {
		this(new LinkedList<>(positionalArguments), null);
	}

	/**
	 * Creates a parse context with named arguments only.
	 */
	public ParseContext(@Nonnull Map<String, Object> namedArguments) {
		this(null, namedArguments);
	}

	/**
	 * Creates a parse context with both named and positional (varargs) arguments.
	 */
	public ParseContext(@Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
		this(new LinkedList<>(Arrays.asList(positionalArguments)), namedArguments);
	}

	/**
	 * Creates a parse context with both named and positional (list) arguments.
	 */
	public ParseContext(@Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
		this(new LinkedList<>(positionalArguments), namedArguments);
	}

	/**
	 * Resolves the next positional argument and checks its data type. The syntax tree must be parsed synchronously in
	 * query order, because each placeholder takes the next argument and every argument can be used only once.
	 * A bound null is a legal argument and is returned as null; a missing argument throws.
	 *
	 * @param <A> the type the calling visitor expects
	 * @return the bound value or null; a collection that is not serializable itself is returned as a list of its items
	 * @throws EvitaInvalidUsageException when no positional argument is left, when the argument is an `Iterable` that
	 *                                    is not a `Collection`, or when the argument, its array component or one of
	 *                                    its items cannot be bound, a NaN or an infinite `Float` or `Double` included
	 */
	@Nullable
	public <A extends Serializable & Comparable<?>> A getNextPositionalArgument() {
		Assert.notNull(
			this.positionalArguments,
			"Query uses positional parameters but no positional arguments were passed."
		);
		final Object argument;
		try {
			this.lastPositionalArgumentIndex++;
			argument = this.positionalArguments.remove();
		} catch (NoSuchElementException e) {
			throw new EvitaInvalidUsageException(
				"Missing argument of index " + this.lastPositionalArgumentIndex + "."
			);
		}
		//noinspection unchecked
		return (A) checkArgument(argument, "Positional argument of index " + this.lastPositionalArgumentIndex);
	}

	/**
	 * Checks that the parsed query used every positional argument the client passed, and refuses it when more
	 * arguments were passed than its placeholders took. Each placeholder takes the next argument when it is visited,
	 * so an argument left over usually means a placeholder was never visited and the ones after it took the values
	 * meant for their predecessors; it is refused rather than ignored. Named arguments are not checked, because
	 * a query may use a named argument any number of times, including never.
	 *
	 * It must run only after the whole query was parsed: until then the placeholders not yet visited still have
	 * arguments waiting for them.
	 *
	 * @throws EvitaSyntaxException when some positional arguments were not used by any placeholder
	 */
	public void assertAllPositionalArgumentsUsed() {
		if (this.positionalArguments != null && !this.positionalArguments.isEmpty()) {
			final int used = this.lastPositionalArgumentIndex + 1;
			throw new EvitaSyntaxException(
				0, 0,
				(used + this.positionalArguments.size()) + " positional arguments were passed, but the query has only "
					+ used + " positional parameters. Every positional argument must be used by exactly one `?`."
			);
		}
	}

	/**
	 * Resolves a named argument by the name used in the query and checks its data type. Unlike a positional
	 * argument, a named argument can be used any number of times in a query.
	 * A name bound to null is a legal argument and is returned as null; a name that is not bound at all throws.
	 *
	 * @param name the parameter name without its `@` prefix
	 * @param <A> the type the calling visitor expects
	 * @return the bound value or null; a collection that is not serializable itself is returned as a list of its items
	 * @throws EvitaInvalidUsageException when no named arguments were passed or the name is not bound, when the
	 *                                    argument is an `Iterable` that is not a `Collection`, or when the argument,
	 *                                    its array component or one of its items cannot be bound, a NaN or an
	 *                                    infinite `Float` or `Double` included
	 */
	@Nullable
	public <A extends Serializable & Comparable<?>> A getNamedArgument(@Nonnull String name) {
		Assert.notNull(
			this.namedArguments,
			"Query uses named parameters but no named arguments were passed."
		);
		final Object argument = this.namedArguments.get(name);
		Assert.isTrue(this.namedArguments.containsKey(name), "Missing argument of name `" + name + "`.");
		//noinspection unchecked
		return (A) checkArgument(argument, "Named argument of name `" + name + "`");
	}

	/**
	 * Checks the data type of a bound argument and returns it in the form the visitors read. A bound iterable must be
	 * a collection: the check below reads its items before the visitor does, so an iterable that can be read only
	 * once would reach the visitor empty, and the visitors need a serializable value. A collection that is not
	 * serializable itself, such as the values of a map, is therefore passed on as a list.
	 *
	 * @param argument bound argument, null for an undefined value
	 * @param description how an error message names the argument
	 * @return the argument, or a list with the items of a collection that is not serializable
	 * @throws EvitaInvalidUsageException when the argument is an iterable but not a collection, or has a data type
	 *                                    the query language does not support
	 */
	@Nullable
	private static Object checkArgument(@Nullable Object argument, @Nonnull String description) {
		Assert.isTrue(
			!(argument instanceof Iterable<?>) || argument instanceof Collection<?>,
			() -> description + " is an `Iterable` that is not a `Collection`. Bind a list or an array instead."
		);
		final Class<?> unsupportedType = findUnsupportedArgumentType(argument);
		if (unsupportedType != null) {
			throw new EvitaInvalidUsageException(
				description + " has unsupported data type `" + unsupportedType.getTypeName() + "`."
			);
		}
		return argument instanceof Collection<?> collection && !(argument instanceof Serializable) ?
			new ArrayList<>(collection) : argument;
	}

	/**
	 * Finds the first unsupported type in a binding. Visitors decide whether an array has meaning as an item of
	 * a bound array or collection. Null represents an undefined value and passes through.
	 *
	 * @param argument bound argument, null for an undefined value
	 * @return the unsupported scalar, array component or list item type, or null when all values can be bound
	 */
	@Nullable
	private static Class<?> findUnsupportedArgumentType(@Nullable Object argument) {
		if (argument != null && argument.getClass().isArray()) {
			return findUnsupportedArgumentArrayType(argument, true);
		} else if (argument instanceof Collection<?> collection) {
			for (final Object item : collection) {
				final Class<?> unsupportedType = findUnsupportedArgumentValueType(item, true);
				if (unsupportedType != null) {
					return unsupportedType;
				}
			}
		} else {
			return findUnsupportedArgumentValueType(argument, false);
		}
		return null;
	}

	/**
	 * Checks an array component and its values. A binding can contain only one level of array items.
	 *
	 * @param array the bound array or an array item of a binding
	 * @param allowArrayItems whether array items can be validated as arrays
	 * @return the first unsupported component or item type, or null if all items can be bound
	 */
	@Nullable
	private static Class<?> findUnsupportedArgumentArrayType(
		@Nonnull Object array, boolean allowArrayItems
	) {
		final Class<?> component = array.getClass().getComponentType();
		if (
			component != Object.class && component != Serializable.class && !component.isArray()
				&& !isArgumentValueTypeSupported(component)
		) {
			return component;
		}
		final int length = Array.getLength(array);
		for (int i = 0; i < length; i++) {
			final Class<?> unsupportedType = findUnsupportedArgumentValueType(Array.get(array, i), allowArrayItems);
			if (unsupportedType != null) {
				return unsupportedType;
			}
		}
		return null;
	}

	/**
	 * Checks a scalar parameter or a single item in a bound array or collection.
	 *
	 * @param value the value to check, null for an undefined value
	 * @param allowArray whether this value can be an array of bound scalars
	 * @return the unsupported type, or null for a valid binding
	 */
	@Nullable
	private static Class<?> findUnsupportedArgumentValueType(@Nullable Object value, boolean allowArray) {
		if (value == null) {
			return null;
		}
		final Class<?> type = value.getClass();
		if (allowArray && type.isArray()) {
			return findUnsupportedArgumentArrayType(value, false);
		}
		// the Java factories refuse a NaN or an infinity when they normalize a floating-point number to a decimal
		final boolean nonFinite = value instanceof Double doubleValue && !Double.isFinite(doubleValue)
			|| value instanceof Float floatValue && !Float.isFinite(floatValue);
		return !isArgumentValueTypeSupported(type) || nonFinite ? type : null;
	}

	/**
	 * Checks which types can reach the visitor for position-specific validation. Floats and doubles, boxed or as the
	 * primitive components of a bound array, reach the visitors, which allow them where the Java factories normalize
	 * them to decimals: the attribute comparisons, `attributeBetween`, `attributeInSet`, `attributeSetExact`,
	 * `attributeInRange` and `histogramHaving`. Their values must also be finite.
	 *
	 * @param type the scalar or array component type
	 * @return whether the type is eligible for binding
	 */
	private static boolean isArgumentValueTypeSupported(@Nonnull Class<?> type) {
		return EvitaDataTypes.isSupportedType(type) || type.isEnum()
			|| type == Float.class || type == Double.class || type == float.class || type == double.class;
	}
}
