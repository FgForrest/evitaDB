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
import io.evitadb.utils.ArrayUtils;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Optional;

/**
 * The `accompanyingPriceContent` constraint defines that entity should have another price calculated with a different
 * price list sequence than the price for sale (but this accompanying price cannot be calculated without also calculating
 * price for sale).
 *
 * <pre>
 * accompanyingPriceContent(
 *     "myCalculatedPrice",
 *     "reference",
 *     "basic"
 * )
 * </pre>
 *
 * First argument is the name of the accompanying price that should be used to label the price calculation. Second and
 * subsequent arguments are names of price lists that should be used for default accompanying price calculation.
 * The order of price lists is important, because it defines the order in which the prices are used in calculation.
 *
 * You can also use {@link DefaultAccompanyingPriceLists} constraint to define default rules for accompanying price
 * and then use only simple form of this constraint without arguments:
 *
 * <pre>
 *     accompanyingPriceContent()
 * </pre>
 *
 * Calculated price will be labeled as `default` and will use price lists defined in `defaultAccompanyingPriceLists` constraint.
 *
 * <p><a href="https://evitadb.io/documentation/query/requirements/price#accompanying-price">Visit detailed user documentation</a></p>
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@ConstraintDefinition(
	name = "accompanyingPriceContent",
	shortDescription = "The requirement defines the ordered price list names that should be used for calculation of" +
		" so-called accompanying price, which is a price not used for selling, but rather for displaying additional" +
		" price information (such as \"previous price\", \"recommended price\", etc.)..",
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
	 * Returns primary keys of all price lists that should be used for default accompanying price calculation.
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

	@Override
	public <T extends EntityContentRequire> boolean isCombinableWith(@Nonnull T anotherRequirement) {
		return anotherRequirement instanceof AccompanyingPriceContent;
	}

	@Nonnull
	@Override
	public <T extends EntityContentRequire> T combineWith(@Nonnull T anotherRequirement) {
		return anotherRequirement;
	}

	@Override
	public <T extends EntityContentRequire> boolean isFullyContainedWithin(@Nonnull T anotherRequirement) {
		return false;
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