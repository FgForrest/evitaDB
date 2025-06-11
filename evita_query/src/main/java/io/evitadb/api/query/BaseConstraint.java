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

package io.evitadb.api.query;

import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.StringUtils;
import lombok.EqualsAndHashCode;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static java.util.Optional.ofNullable;

/**
 * Base query defines shared behaviour for all constraints.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@EqualsAndHashCode(of = {"name", "arguments"})
public abstract class BaseConstraint<T extends Constraint<T>> implements Constraint<T> {
	@Serial private static final long serialVersionUID = 2216675116416057520L;
	private final String name;
	private final Serializable[] arguments;

	@Nonnull
	public static String convertToString(@Nullable Serializable value) {
		if (value == null) {
			return "<NULL>";
		}
		return EvitaDataTypes.formatValue(value);
	}

	protected BaseConstraint(@Nonnull Serializable... arguments) {
		super();
		this.name = getDefaultName();
		if (Arrays.stream(arguments).anyMatch(it -> it != null && !EvitaDataTypes.isSupportedTypeOrItsArrayOrEnum(it.getClass()))) {
			this.arguments = Arrays.stream(arguments)
				.map(EvitaDataTypes::toSupportedType)
				.toArray(Serializable[]::new);
		} else {
			this.arguments = arguments;
		}
	}

	@Nonnull
	protected String getDefaultName() {
		return Objects.requireNonNull(StringUtils.uncapitalize(this.getClass().getSimpleName()));
	}

	protected BaseConstraint(@Nullable String name, @Nonnull Serializable... arguments) {
		this.name = ofNullable(name).orElseGet(this::getDefaultName);
		if (Arrays.stream(arguments).anyMatch(it -> it != EvitaDataTypes.toSupportedType(it))) {
			this.arguments = Arrays.stream(arguments)
				.map(EvitaDataTypes::toSupportedType)
				.toArray(Serializable[]::new);
		} else {
			this.arguments = arguments;
		}
	}

	/**
	 * Name is always derived from the query class name to simplify searching/mapping query language to respective
	 * classes.
	 */
	@Nonnull
	@Override
	public String getName() {
		return this.name + (this instanceof ConstraintWithSuffix cws ?
			cws.getSuffixIfApplied().map(StringUtils::capitalize).orElse("") : "");
	}

	/**
	 * Returns arguments of the query.
	 */
	@Nonnull
	@Override
	public Serializable[] getArguments() {
		return this.arguments;
	}

	/**
	 * Rendering is shared among all constraints - it consists of `constraintName`(`arg1`,`arg2`,`argN`), argument can
	 * be query itself.
	 */
	@Nonnull
	@Override
	public String toString() {
		return getName() +
			ARG_OPENING +
			String.join(",", getSerializedArguments()) +
			ARG_CLOSING;
	}

	/**
	 * Serializes {@link #getArguments()} into individual strings for pretty printing.
	 */
	@Nonnull
	protected List<String> getSerializedArguments() {
		final Serializable[] arguments = (this instanceof ConstraintWithDefaults<?> constraintWithDefaults) ? constraintWithDefaults.getArgumentsExcludingDefaults() : getArguments();
		final List<String> serializedArguments = new ArrayList<>(arguments.length);
		for (int i = 0; i < arguments.length; i++) {
			final Serializable argument = arguments[i];
			if (!(this instanceof ConstraintWithSuffix cws) || !cws.isArgumentImplicitForSuffix(i, argument)) {
				serializedArguments.add(BaseConstraint.convertToString(argument));
			}
		}
		return serializedArguments;
	}

	/**
	 * Checks if all arguments are non-null.
	 */
	protected boolean isArgumentsNonNull() {
		for (Serializable argument : getArguments()) {
			if (argument == null) {
				return false;
			}
		}
		return true;
	}

}
