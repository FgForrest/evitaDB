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

package io.evitadb.api.query.order;

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.GenericConstraint;
import io.evitadb.api.query.OrderConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * Random ordering is useful in situations where you want to present the end user with the unique entity listing every
 * time he/she accesses it. The constraint makes the order of the entities in the result random and does not take any
 * arguments.
 *
 * Example:
 *
 * <pre>
 * random()
 * </pre>
 *
 * If you need to make output random, but always random in the same way (e.g. for testing purposes, or for consistent
 * output for a given user), you can use the `seed` constraint to provide a seed for the random number generator.
 *
 * Example:
 *
 * <pre>
 * randomWithSeed(42)
 * </pre>
 *
 * <p><a href="https://evitadb.io/documentation/query/ordering/random#random">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "random",
	shortDescription = "The constraint sorts returned entities randomly.",
	userDocsLink = "/documentation/query/ordering/random#random",
	supportedIn = { ConstraintDomain.ENTITY, ConstraintDomain.REFERENCE, ConstraintDomain.INLINE_REFERENCE }
)
public class Random extends AbstractOrderConstraintLeaf implements GenericConstraint<OrderConstraint>, ConstraintWithSuffix {
	@Serial private static final long serialVersionUID = -7130233965171274166L;
	public static final Random INSTANCE = new Random();
	private static final String SUFFIX = "withSeed";

	private Random(Serializable... arguments) {
		super(arguments);
	}

	@Creator
	public Random() {
	}

	@Creator(suffix = SUFFIX)
	public Random(long seed) {
		super(seed);
	}

	/**
	 * Returns optional seed for the random number generator.
	 *
	 * @return seed for the random number generator or empty if not provided
	 */
	@Nullable
	public Long getSeed() {
		return Arrays.stream(getArguments())
			.filter(Long.class::isInstance)
			.map(Long.class::cast)
			.findFirst()
			.orElse(null);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return ofNullable(getSeed()).map(it -> SUFFIX);
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public OrderConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new Random(newArguments);
	}
}
