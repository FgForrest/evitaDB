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
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Context object shared in all parsing visitors. Contains metadata that should be accessible from any parsing visitor,
 * e.g. client arguments.
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

	public ParseContext() {
		this((Queue<Object>) null, null);
	}

	public ParseContext(@Nonnull Object... positionalArguments) {
		this(
			new LinkedList<>(Arrays.asList(positionalArguments)),
			null
		);
	}

	public ParseContext(@Nonnull List<Object> positionalArguments) {
		this(
			new LinkedList<>(positionalArguments),
			null
		);
	}

	public ParseContext(@Nonnull Map<String, Object> namedArguments) {
		this(
			null,
			namedArguments
		);
	}

	public ParseContext(@Nonnull Map<String, Object> namedArguments, @Nonnull Object... positionalArguments) {
		this(
			new LinkedList<>(Arrays.asList(positionalArguments)),
			namedArguments
		);
	}

	public ParseContext(@Nonnull Map<String, Object> namedArguments, @Nonnull List<Object> positionalArguments) {
		this(
			new LinkedList<>(positionalArguments),
			namedArguments
		);
	}

	/**
	 * Tries to resolve next positional argument. Checks if there are any positional arguments left and if they have
	 * supported data types. This method expects that syntax tree using positional parameters is parsed synchronously
	 * in original order.
	 * Also, each argument can be used only once in query.
	 */
	@Nonnull
	public <A extends Serializable & Comparable<?>> A getNextPositionalArgument() {
		Assert.notNull(this.positionalArguments, "Query uses positional parameters but no positional arguments were passed.");
		final Object argument;
		try {
			this.lastPositionalArgumentIndex++;
			argument = this.positionalArguments.remove();
		} catch (Exception e) {
			throw new EvitaInvalidUsageException(
				"Missing argument of index " + this.lastPositionalArgumentIndex + "."
			);
		}
		Assert.notNull(
			argument,
			"Positional argument of index " + this.lastPositionalArgumentIndex + " is null."
		);
		Assert.isTrue(
			isArgumentDataTypeSupported(argument),
			"Positional argument of index " + this.lastPositionalArgumentIndex + " has unsupported data type."
		);

		//noinspection unchecked
		return (A) argument;
	}

	/**
	 * Tries to resolve named parameter by name specified by client. Checks if there are any positional arguments left and if they have
	 * supported data types. Each argument can be used multiple times in query.
	 */
	@Nonnull
	public <A extends Serializable & Comparable<?>> A getNamedArgument(@Nonnull String name) {
		Assert.notNull(this.namedArguments, "Query uses named parameters but no named arguments were passed.");
		final Object argument = this.namedArguments.get(name);
		Assert.notNull(
			argument,
			"Missing argument of name `" + name + "`."
		);
		Assert.isTrue(
			isArgumentDataTypeSupported(argument),
			"Named argument of name `" + name + "` has unsupported data type."
		);

		//noinspection unchecked
		return (A) argument;
	}

	/**
	 * Supported argument data types are: evita data types, enums, arrays of evita data types and enums, iterables of
	 * evita data types or enums.
	 */
	private static boolean isArgumentDataTypeSupported(@Nonnull Object argument) {
		if (EvitaDataTypes.isSupportedTypeOrItsArray(argument.getClass())) {
			return true;
		}
		if (argument.getClass().isEnum() || (argument.getClass().isArray() && argument.getClass().getComponentType().isEnum())) {
			return true;
		}
		if (argument instanceof final Iterable<?> iterable) {
			final Iterator<?> iterator = iterable.iterator();
			if (iterator.hasNext()) {
				final Object item = iterator.next();
				return EvitaDataTypes.isSupportedType(item.getClass()) || item.getClass().isEnum();
			}
			return true;
		}
		return false;
	}
}
