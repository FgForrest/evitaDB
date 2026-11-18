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
	 * Tries to resolve next positional argument. Checks if there are any positional arguments left and if they
	 * have supported data types. This method expects that syntax tree using positional parameters is parsed
	 * synchronously in original order. Also, each argument can be used only once in query.
	 * A bound null is a legal argument and is returned as null; a missing argument still throws.
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
	 * Tries to resolve named parameter by name specified by client. Checks if there are any positional arguments
	 * left and if they have supported data types. Each argument can be used multiple times in query.
	 * A name bound to null is a legal argument and is returned as null; a name that is not bound at all throws.
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
		Assert.isTrue(isArgumentDataTypeSupported(argument), () -> description + " has unsupported data type.");
		return argument instanceof Collection<?> collection && !(argument instanceof Serializable) ?
			new ArrayList<>(collection) : argument;
	}

	/**
	 * Supported argument data types are: evita data types, enums, arrays of evita data types and enums,
	 * collections of evita data types or enums.
	 * Null, and null items inside arrays and collections, are supported as undefined values; every defined item is
	 * checked.
	 *
	 * @param argument bound argument, null for an undefined value
	 * @return true when the query language supports the argument's data type
	 */
	private static boolean isArgumentDataTypeSupported(@Nullable Object argument) {
		if (argument != null && argument.getClass().isArray()) {
			final Class<?> component = argument.getClass().getComponentType();
			if (component != Object.class && component != Serializable.class
				&& !EvitaDataTypes.isSupportedType(component) && !component.isEnum()) {
				return false;
			}
			final int length = Array.getLength(argument);
			for (int i = 0; i < length; i++) {
				final Object item = Array.get(argument, i);
				if (item != null && !EvitaDataTypes.isSupportedType(item.getClass()) && !item.getClass().isEnum()) {
					return false;
				}
			}
			return true;
		}
		if (argument == null || EvitaDataTypes.isSupportedType(argument.getClass())) {
			return true;
		}
		if (argument.getClass().isEnum()) {
			return true;
		}
		if (argument instanceof final Collection<?> collection) {
			for (final Object item : collection) {
				if (item != null && !EvitaDataTypes.isSupportedType(item.getClass()) && !item.getClass().isEnum()) {
					return false;
				}
			}
			return true;
		}
		return false;
	}
}
