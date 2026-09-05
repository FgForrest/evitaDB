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

package io.evitadb.api.query.require;

import io.evitadb.api.query.ConstraintWithSuffix;
import io.evitadb.api.query.PriceConstraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.descriptor.ConstraintDomain;
import io.evitadb.api.query.descriptor.annotation.ConstraintDefinition;
import io.evitadb.api.query.descriptor.annotation.Creator;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * The `accompanyingPriceContent` requirement instructs the engine to calculate and return an additional price
 * alongside the regular selling price. This is called the *accompanying price* and is typically used to display
 * comparison prices such as "original price", "recommended retail price", or "reference price" in the UI.
 *
 * An accompanying price is calculated using a different price-list sequence than the selling price but shares the
 * same currency, validity date, and price-type rules. Because the accompanying price is derived from the same pricing
 * engine pass, it **cannot be requested without also calculating the selling price** — a `priceContent` requirement
 * (with a mode other than `NONE`) or an active price filter must be present.
 *
 * ## Arguments
 *
 * - **First argument** — the logical name used to label this accompanying price in the result. Multiple
 *   `accompanyingPriceContent` constraints with distinct names can be specified inside the same `entityFetch`
 *   to retrieve multiple accompanying prices simultaneously.
 * - **Subsequent arguments** — an ordered list of price-list names that define the price-list lookup sequence
 *   for this particular accompanying price calculation. The order matters: the engine selects the first matching
 *   price found in the list.
 *
 * ## Default form
 *
 * When no arguments are provided (`accompanyingPriceContentDefault()`), the constraint uses the label
 * `"default"` and delegates the price-list sequence to the {@link DefaultAccompanyingPriceLists} constraint
 * in the same `require` clause. This allows defining the price-list sequence once and reusing it across
 * multiple entity fetches.
 *
 * ## EvitaQL representations
 *
 * Named form with explicit price lists:
 *
 * ```
 * accompanyingPriceContent(
 *     "myCalculatedPrice",
 *     "reference",
 *     "basic"
 * )
 * ```
 *
 * Default form (requires {@link DefaultAccompanyingPriceLists} in the query):
 *
 * ```
 * accompanyingPriceContentDefault()
 * ```
 *
 * ## Two accompanyingPriceContent requirements in one entityFetch
 *
 * The requirement is keyed by the accompanying price **name**, which is what makes several of them in one
 * `entityFetch` the normal case: two constraints naming different prices calculate two independent prices and both
 * survive. Two constraints naming the *same* price are folded into one when they list exactly the same price lists,
 * and are refused with an {@link EvitaInvalidUsageException} when they do not — including when the two lists differ
 * only in order, because the sequence is a priority order and any merge would invent a priority neither side asked
 * for. See {@link #combineWith(EntityContentRequire)} for the reasoning.
 *
 * [Visit detailed user documentation](https://evitadb.io/documentation/query/requirements/price#accompanying-price)
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "accompanyingPriceContent",
	shortDescription = "The constraint defines ordered price list names used to calculate an accompanying price" +
		" — a non-selling price shown for comparison (e.g. 'previous price', 'recommended retail price').",
	userDocsLink = "/documentation/query/requirements/price#accompanying-price",
	supportedIn = ConstraintDomain.ENTITY
)
public class AccompanyingPriceContent
	extends AbstractRequireConstraintLeaf
	implements PriceConstraint<RequireConstraint>, EntityContentRequire, RequireConstraint, ConstraintWithSuffix {

	public static final String DEFAULT_ACCOMPANYING_PRICE = "default";
	@Serial private static final long serialVersionUID = -5786325458930138452L;
	public static final String SUFFIX = "default";

	private AccompanyingPriceContent(@Nonnull Serializable... arguments) {
		super(arguments);
	}

	@Creator(
		suffix = SUFFIX
	)
	public AccompanyingPriceContent() {
		super(new Serializable[] { DEFAULT_ACCOMPANYING_PRICE });
	}

	@Creator
	public AccompanyingPriceContent(@Nonnull String accompanyingPriceName, @Nonnull String... priceLists) {
		super(
			ArrayUtils.mergeArrays(
				new Serializable[] {accompanyingPriceName},
				priceLists
			)
		);
	}

	/**
	 * Returns the accompanying price name that should be used to label the price calculation.
	 *
	 * @return accompanying price name to label the price calculation
	 */
	@Nonnull
	public Optional<String> getAccompanyingPriceName() {
		final Serializable[] args = getArguments();
		return args.length == 0 ? Optional.empty() : Optional.of((String) args[0]);
	}

	/**
	 * Returns names of all price lists that should be used for default accompanying price calculation.
	 */
	@Nonnull
	public String[] getPriceLists() {
		return Arrays.stream(getArguments()).skip(1).map(String.class::cast).toArray(String[]::new);
	}

	@Override
	public boolean isApplicable() {
		return isArgumentsNonNull();
	}

	@Nonnull
	@Override
	public RequireConstraint cloneWithArguments(@Nonnull Serializable[] newArguments) {
		Assert.isTrue(
			newArguments.length > 0 && newArguments[0] instanceof String,
			"First argument must be a non-null accompanying price name!"
		);
		return new AccompanyingPriceContent(newArguments);
	}

	/**
	 * Two accompanying price requirements describe the same thing only when they calculate the **same** accompanying
	 * price, which is identified by its name. Requirements with distinct names are requested side by side on purpose -
	 * that is the whole point of the constraint - and must never be folded into one, so they report as not combinable.
	 *
	 * @param anotherRequirement another requirement to be combined with
	 * @param <T> type of the requirement to be combined with
	 * @return true when the other requirement calculates an accompanying price of the very same name
	 */
	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof AccompanyingPriceContent anotherAccompanyingPrice &&
			getAccompanyingPriceNameOrDefault().equals(anotherAccompanyingPrice.getAccompanyingPriceNameOrDefault());
	}

	/**
	 * Combines two requirements calculating the accompanying price of the same name.
	 *
	 * Because the price lists of an accompanying price form an ordered **priority** sequence - the engine takes the
	 * first price it finds walking the sequence - two different sequences have no meaningful union: any merge would
	 * invent a priority order neither side asked for, and would silently change the price the client gets. Two
	 * requirements for one price name therefore have to agree on the price lists exactly, order included, and a
	 * disagreement is refused with an {@link EvitaInvalidUsageException}.
	 *
	 * @param anotherRequirement another requirement to be combined with, must be an accompanying price content
	 *                           requirement with the same accompanying price name
	 * @param <T> type of the requirement to be combined with
	 * @return this very instance, since equal requirements have nothing to merge
	 * @throws GenericEvitaInternalError when the other requirement is of a different type or calculates a
	 *                                   differently named accompanying price - both are caller bugs, since
	 *                                   {@link #isCombinableWith(EntityContentRequire)} rejects such a pair
	 * @throws EvitaInvalidUsageException when both requirements name the same accompanying price but disagree on the
	 *                                    price lists to calculate it from
	 */
	@Nonnull
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		if (!(anotherRequirement instanceof AccompanyingPriceContent anotherAccompanyingPrice)) {
			throw new GenericEvitaInternalError(
				"Only accompanying price content requirement can be combined with this one - but got: " +
					anotherRequirement.getClass(),
				"Only accompanying price content requirement can be combined with this one!"
			);
		}
		final String priceName = getAccompanyingPriceNameOrDefault();
		if (!priceName.equals(anotherAccompanyingPrice.getAccompanyingPriceNameOrDefault())) {
			throw new GenericEvitaInternalError(
				"Only accompanying price content requirements calculating the price of the same name can be " +
					"combined - but got: " + this + " and " + anotherRequirement + "!",
				"Only accompanying price content requirements calculating the price of the same name can be combined!"
			);
		}
		if (!Arrays.equals(getPriceLists(), anotherAccompanyingPrice.getPriceLists())) {
			final String reason = "Cannot combine multiple accompanying price content requirements for price `" +
				priceName + "` with different price lists";
			throw new EvitaInvalidUsageException(
				reason + ": " + this + " and " + anotherRequirement + ".",
				reason + "."
			);
		}
		//noinspection unchecked
		return (T) this;
	}

	/**
	 * An accompanying price requirement is satisfied by another one only when that one calculates the price of the
	 * same name from exactly the same price list sequence - anything else would compute a different price and the
	 * requirement would not be fulfilled.
	 *
	 * @param anotherRequirement another requirement to be checked for containment
	 * @param <T> the type of the requirement which extends EntityContentRequire
	 * @return true when the other requirement calculates the very same accompanying price
	 */
	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof AccompanyingPriceContent anotherAccompanyingPrice &&
			getAccompanyingPriceNameOrDefault().equals(anotherAccompanyingPrice.getAccompanyingPriceNameOrDefault()) &&
			Arrays.equals(getPriceLists(), anotherAccompanyingPrice.getPriceLists());
	}

	/**
	 * Returns the name identifying the accompanying price this requirement calculates - the key two requirements have
	 * to share to describe the same price. Falls back to {@link #DEFAULT_ACCOMPANYING_PRICE} for a constraint that
	 * carries no arguments at all.
	 *
	 * @return name of the calculated accompanying price, never null
	 */
	@Nonnull
	private String getAccompanyingPriceNameOrDefault() {
		return getAccompanyingPriceName().orElse(DEFAULT_ACCOMPANYING_PRICE);
	}

	@Nonnull
	@Override
	public Optional<String> getSuffixIfApplied() {
		if (getAccompanyingPriceName().filter(DEFAULT_ACCOMPANYING_PRICE::equals).orElse(null) != null) {
			return Optional.of(SUFFIX);
		}
		return Optional.empty();
	}

	@Override
	public boolean isArgumentImplicitForSuffix(int argumentPosition, @Nonnull Serializable argument) {
		// we want to omit the default accompanying price name
		return argumentPosition == 0 && DEFAULT_ACCOMPANYING_PRICE.equals(argument);
	}
}