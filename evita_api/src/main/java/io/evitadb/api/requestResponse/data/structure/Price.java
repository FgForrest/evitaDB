/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.AccessLevel;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Delegate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.annotation.concurrent.ThreadSafe;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;

import static java.util.Optional.ofNullable;

/**
 * Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce systems
 * and highly affects performance of the entities filtering and sorting, they deserve first class support in entity model.
 * It is pretty common in B2B systems single product has assigned dozens of prices for the different customers.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link InitialEntityBuilder}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Immutable
@ThreadSafe
@Data
@EqualsAndHashCode(of = {"version", "priceKey"})
public class Price implements PriceContract {
	@Serial private static final long serialVersionUID = -7355665177038792532L;
	private static final String PRICE_KEY_IS_MANDATORY_VALUE = "Price key is mandatory value!";
	private static final String PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE = "Price without tax is mandatory value!";
	private static final String PRICE_TAX_IS_MANDATORY_VALUE = "Price tax is mandatory value!";
	private static final String PRICE_WITH_TAX_IS_MANDATORY_VALUE = "Price with tax is mandatory value!";
	private static final String PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE = "Price inner record id must be positive value!";

	/**
	 * Contains version of this object and gets increased with any entity update. Allows to execute
	 * optimistic locking i.e. avoiding parallel modifications.
	 */
	private final int version;
	/**
	 * Primary identification of the price consisting of external price id, price list and currency.
	 * @see PriceKey for details
	 */
	@Delegate
	@Nonnull
	private final PriceKey priceKey;
	/**
	 * Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
	 * so that the aggregating product can represent them in certain views on the product. In that case there is need
	 * to distinguish the projected prices of the subordinate product in the one that represents them.
	 *
	 * Inner record id must contain positive value.
	 */
	private final Integer innerRecordId;
	/**
	 * Price without tax.
	 */
	private final BigDecimal priceWithoutTax;
	/**
	 * Tax rate percentage (i.e. for 19% it'll be 19.00)
	 */
	private final BigDecimal taxRate;
	/**
	 * Price with tax.
	 */
	private final BigDecimal priceWithTax;
	/**
	 * Date and time interval for which the price is valid (inclusive).
	 */
	private final DateTimeRange validity;
	/**
	 * Controls whether price is subject to filtering / sorting logic, non-sellable prices will be fetched along with
	 * entity but won't be considered when evaluating search {@link io.evitadb.api.query.Query}. These prices may be
	 * used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
	 * as "usual price") but are not considered as the "selling" price.
	 */
	private final boolean sellable;
	/**
	 * Contains TRUE if price was dropped - i.e. removed. Prices is not removed (unless tidying process
	 * does it), but are lying among other prices with tombstone flag. Dropped prices can be overwritten by
	 * a new value continuing with the versioning where it was stopped for the last time.
	 */
	private final boolean dropped;

	public Price(
		int version,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable,
		boolean dropped
	) {
		Assert.notNull(priceKey, PRICE_KEY_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithoutTax, PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(taxRate, PRICE_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithTax, PRICE_WITH_TAX_IS_MANDATORY_VALUE);
		Assert.isTrue(innerRecordId == null || innerRecordId > 0, PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE);
		this.version = version;
		this.priceKey = priceKey;
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.sellable = sellable;
		this.dropped = dropped;
	}

	public Price(
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable
	) {
		Assert.notNull(priceKey, PRICE_KEY_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithoutTax, PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(taxRate, PRICE_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithTax, PRICE_WITH_TAX_IS_MANDATORY_VALUE);
		Assert.isTrue(innerRecordId == null || innerRecordId > 0, PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE);
		this.version = 1;
		this.priceKey = priceKey;
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.sellable = sellable;
		this.dropped = false;
	}

	public Price(
		int version,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean sellable
	) {
		Assert.notNull(priceKey, PRICE_KEY_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithoutTax, PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(taxRate, PRICE_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithTax, PRICE_WITH_TAX_IS_MANDATORY_VALUE);
		Assert.isTrue(innerRecordId == null || innerRecordId > 0, PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE);
		this.version = version;
		this.priceKey = priceKey;
		this.innerRecordId = innerRecordId;
		this.priceWithoutTax = priceWithoutTax;
		this.taxRate = taxRate;
		this.priceWithTax = priceWithTax;
		this.validity = validity;
		this.sellable = sellable;
		this.dropped = false;
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// version
			MemoryMeasuringConstants.INT_SIZE +
			// dropped
			MemoryMeasuringConstants.BYTE_SIZE +
			// sellable
			MemoryMeasuringConstants.BYTE_SIZE +
			// key
			MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// price id
			MemoryMeasuringConstants.INT_SIZE +
			// price list
			EvitaDataTypes.estimateSize(priceKey.getPriceList()) +
			// currency
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// inner record id
			MemoryMeasuringConstants.REFERENCE_SIZE + ofNullable(innerRecordId).map(it -> MemoryMeasuringConstants.INT_SIZE).orElse(0) +
			// price without and with tax + tax
			3 * (MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.BIG_DECIMAL_SIZE) +
			// validity
			MemoryMeasuringConstants.REFERENCE_SIZE + ofNullable(validity).stream().mapToInt(EvitaDataTypes::estimateSize).sum();
	}

	@Override
	public String toString() {
		return  (dropped ? "❌ " : "") +
			"\uD83D\uDCB0 " + (sellable ? "\uD83D\uDCB5 " : "") + priceWithTax + " " + priceKey.getCurrency() + " (" + taxRate + "%)" +
				", price list " + priceKey.getPriceList() +
				(validity == null ? "": ", valid in " + validity) +
				", external id " + priceKey.getPriceId() +
				(innerRecordId == null ? "" : "/" + innerRecordId);
	}

	/**
	 * Primary key of the {@link Price}. Price is uniquely identified by combination: priceId, priceList, currency.
	 */
	@Data
	@Immutable
	@ThreadSafe
	public static class PriceKey implements Serializable, Comparable<PriceKey> {
		@Serial private static final long serialVersionUID = -4115511848409188910L;

		/**
		 * Contains the identification of the price in the external systems. This ID is expected to be used for
		 * synchronization of the price in relation to the primary source of the prices. The price with the same ID
		 * must be unique within the same entity. The prices with the same ID in multiple entities should represent
		 * the same price in terms of other values - such as validity, currency, price list, the price itself, and all
		 * other properties. These values can be different for a limited time (for example, the prices of Entity A and
		 * Entity B can be the same, but Entity A is updated in a different session/transaction and at a different time
		 * than Entity B).
		 */
		private final int priceId;
		/**
		 * Contains identification of the price list in the external system. Each price must reference a price list. Price list
		 * identification may refer to another Evita entity or may contain any external price list identification
		 * (for example id or unique name of the price list in the external system).
		 *
		 * Single entity is expected to have single price for the price list unless there is {@link #validity} specified.
		 * In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
		 * in the same price list.
		 */
		private final String priceList;
		/**
		 * Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
		 */
		private final Currency currency;

		public PriceKey(int priceId, @Nonnull String priceList, @Nonnull Currency currency) {
			Assert.notNull(priceList, "Price list name is mandatory value!");
			Assert.notNull(currency, "Price currency is mandatory value!");
			this.priceId = priceId;
			this.priceList = priceList;
			this.currency = currency;
		}

		@Override
		public int compareTo(PriceKey o) {
			int result = currency.getCurrencyCode().compareTo(o.currency.getCurrencyCode());
			if (result == 0) {
				result = priceList.compareTo(o.priceList);
				if (result == 0) {
					return Integer.compare(priceId, o.priceId);
				} else {
					return result;
				}
			} else {
				return result;
			}
		}

		@Override
		public String toString() {
			return "\uD83D\uDCB0 " + priceId + " in " + priceList + " " + currency + " ";
		}
	}

	/**
	 * This comparator sorts {@link PriceKey} by price id first, then by currency and then by price list.
	 * It differs from default comparison logic in {@link PriceKey#compareTo(PriceKey)} that sorts by currency first,
	 * then price list and finally by price id.
	 */
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class PriceIdFirstPriceKeyComparator implements Comparator<PriceKey>, Serializable {
		@Serial private static final long serialVersionUID = -1011508715822385723L;
		public static final PriceIdFirstPriceKeyComparator INSTANCE = new PriceIdFirstPriceKeyComparator();

		@Override
		public int compare(PriceKey o1, PriceKey o2) {
			int result = Integer.compare(o1.getPriceId(), o2.getPriceId());
			if (result == 0) {
				result = o1.getCurrency().getCurrencyCode().compareTo(o2.currency.getCurrencyCode());
				if (result == 0) {
					return o1.priceList.compareTo(o2.priceList);
				} else {
					return result;
				}
			} else {
				return result;
			}
		}

	}

}
