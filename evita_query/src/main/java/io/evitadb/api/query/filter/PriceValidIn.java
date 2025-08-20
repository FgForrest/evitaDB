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

package io.evitadb.api.query.filter;

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.FilterConstraint;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Supplier;

import static java.util.Optional.empty;
import static java.util.Optional.of;

/**
 * The `priceValidIn` excludes all entities that don't have a valid price for sale at the specified date and time. If
 * the price doesn't have a validity property specified, it passes all validity checks.
 *
 * Example:
 *
 * <pre>
 * priceValidIn(2020-07-30T20:37:50+00:00)
 * </pre>
 *
 * Warning: Only a single occurrence of any of this constraint is allowed in the filter part of the query.
 * Currently, there is no way to switch context between different parts of the filter and build queries such as find
 * a product whose price is either in "CZK" or "EUR" currency at this or that time using this constraint.
 *
 * <p><a href="https://evitadb.io/documentation/filtering/price#price-valid-in">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "validIn",
	shortDescription = "The constraint checks if entity has selling price valid at the passed moment.",
	userDocsLink = "/documentation/filtering/price#price-valid-in",
	supportedIn = ConstraintDomain.ENTITY
)
public class PriceValidIn extends AbstractFilterConstraintLeaf
	implements PriceConstraint<FilterConstraint>, ConstraintWithSuffix, FilterConstraint {
	@Serial private static final long serialVersionUID = -3041416427283645494L;
	private static final String SUFFIX = "now";

	private PriceValidIn(Serializable... arguments) {
		super(arguments);
	}

	@Creator(suffix = SUFFIX)
	public PriceValidIn() {
		super();
	}

	@Creator
	public PriceValidIn(@Nonnull OffsetDateTime theMoment) {
		super(theMoment);
	}

	/**
	 * Returns {@link OffsetDateTime} that should be verified whether is within the range (inclusive) of price validity.
	 */
	@Nullable
	public OffsetDateTime getTheMoment(@Nonnull Supplier<OffsetDateTime> currentDateAndTime) {
		return getArguments().length == 0 ? currentDateAndTime.get() : (OffsetDateTime) getArguments()[0];
	}

	/**
	 * Returns {@link OffsetDateTime} that should be verified whether is within the range (inclusive) of price validity.
	 * Note for internal use only, uses current date and time.
	 */
	@Nullable
	private OffsetDateTime getTheMoment() {
		return getTheMoment(OffsetDateTime::now);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		return getArguments().length == 0 ? of(SUFFIX) : empty();
	}

	@Override
	public boolean isApplicable() {
		return true;
	}

	@Nonnull
	@Override
	public FilterConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		return new PriceValidIn(newArguments);
	}
}
