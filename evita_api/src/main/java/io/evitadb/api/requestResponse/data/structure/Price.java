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

package io.evitadb.api.requestResponse.data.structure;

import io.evitadb.api.proxy.impl.entityBuilder.SetPriceMethodClassifier;
import io.evitadb.api.requestResponse.data.PriceContract;
import io.evitadb.dataType.DateTimeRange;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Currency;
import java.util.Objects;

import static java.util.Optional.ofNullable;

/**
 * Prices are specific to a very few entities, but because correct price computation is very complex in e-commerce systems
 * and highly affects performance of the entities filtering and sorting, they deserve first class support in entity model.
 * It is pretty common in B2B systems single product has assigned dozens of prices for the different customers.
 *
 * Class is immutable on purpose - we want to support caching the entities in a shared cache and accessed by many threads.
 * For altering the contents use {@link InitialEntityBuilder}.
 *
 * @param version         Contains version of this object and gets increased with any entity update. Allows to execute
 *                        optimistic locking i.e. avoiding parallel modifications.
 * @param priceKey        Primary identification of the price consisting of external price id, price list and currency.
 * @param innerRecordId   Some special products (such as master products, or product sets) may contain prices of all "subordinate" products
 *                        so that the aggregating product can represent them in certain views on the product. In that case there is need
 *                        to distinguish the projected prices of the subordinate product in the one that represents them.
 *
 *                        Inner record id must contain positive value.
 * @param priceWithoutTax Price without tax.
 * @param priceWithTax    Price without tax.
 * @param taxRate         Tax rate percentage (i.e. for 19% it'll be 19.00)
 * @param validity        Date and time interval for which the price is valid (inclusive).
 * @param indexed        Controls whether price is subject to filtering / sorting logic, non-indexed prices will be fetched along with
 *                        entity but won't be considered when evaluating search {@link io.evitadb.api.query.Query}. These prices may be
 *                        used for "informational" prices such as reference price (the crossed out price often found on e-commerce sites
 *                        as "usual price") but are not considered as the "selling" price.
 * @param dropped         Contains TRUE if price was dropped - i.e. removed. Prices is not removed (unless tidying process
 *                        does it), but are lying among other prices with tombstone flag. Dropped prices can be overwritten by
 *                        a new value continuing with the versioning where it was stopped for the last time.
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 * @see PriceKey for details
 */
public record Price(
	int version,
	@Nonnull PriceKey priceKey,
	@Nullable Integer innerRecordId,
	@Nonnull BigDecimal priceWithoutTax,
	@Nonnull BigDecimal taxRate,
	@Nonnull BigDecimal priceWithTax,
	@Nullable DateTimeRange validity,
	boolean indexed,
	boolean dropped
) implements PriceContract {
	@Serial private static final long serialVersionUID = -7355665177038792532L;
	private static final String PRICE_KEY_IS_MANDATORY_VALUE = "Price key is mandatory value!";
	private static final String PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE = "Price without tax is mandatory value!";
	private static final String PRICE_TAX_IS_MANDATORY_VALUE = "Price tax is mandatory value!";
	private static final String PRICE_WITH_TAX_IS_MANDATORY_VALUE = "Price with tax is mandatory value!";
	private static final String PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE = "Price inner record id must be positive value!";

	public Price {
		Assert.notNull(priceKey, PRICE_KEY_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithoutTax, PRICE_WITHOUT_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(taxRate, PRICE_TAX_IS_MANDATORY_VALUE);
		Assert.notNull(priceWithTax, PRICE_WITH_TAX_IS_MANDATORY_VALUE);
		Assert.isTrue(innerRecordId == null || innerRecordId > 0, PRICE_INNER_RECORD_ID_MUST_BE_POSITIVE_VALUE);
	}

	public Price(
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		this(1, priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed, false);
	}

	public Price(
		int version,
		@Nonnull PriceKey priceKey,
		@Nullable Integer innerRecordId,
		@Nonnull BigDecimal priceWithoutTax,
		@Nonnull BigDecimal taxRate,
		@Nonnull BigDecimal priceWithTax,
		@Nullable DateTimeRange validity,
		boolean indexed
	) {
		this(version, priceKey, innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed, false);
	}

	/**
	 * This constructor is used by {@link SetPriceMethodClassifier}.
	 *
	 * @param priceId         the identification of the price in the external systems
	 * @param priceList       identification of the price list in the external system
	 * @param currency        identification of the currency
	 * @param innerRecordId   some special products (such as master products, or product sets) may contain prices of all "subordinate" products
	 * @param priceWithoutTax price without tax
	 * @param taxRate         tax rate percentage (i.e. for 19% it'll be 19.00)
	 * @param priceWithTax    price with tax
	 * @param validity        date and time interval for which the price is valid (inclusive)
	 * @param indexed        controls whether price is subject to filtering / sorting logic
	 */
	public Price(
		int priceId, //0
		@Nonnull String priceList, //1
		@Nonnull Currency currency, //2
		@Nullable Integer innerRecordId, //3
		@Nonnull BigDecimal priceWithoutTax, //4
		@Nonnull BigDecimal taxRate, //5
		@Nonnull BigDecimal priceWithTax, //6
		@Nullable DateTimeRange validity, //7
		boolean indexed //8
	) {
		this(0, new PriceKey(priceId, priceList, currency), innerRecordId, priceWithoutTax, taxRate, priceWithTax, validity, indexed, false);
	}

	@Override
	public int priceId() {
		return this.priceKey.priceId();
	}

	@Nonnull
	@Override
	public String priceList() {
		return this.priceKey.priceList();
	}

	@Nonnull
	@Override
	public Currency currency() {
		return this.priceKey.currency();
	}

	@Override
	public boolean relatesTo(@Nonnull PriceContract anotherPrice) {
		return Objects.equals(this.innerRecordId, anotherPrice.innerRecordId());
	}

	@Override
	public int estimateSize() {
		return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// version
			MemoryMeasuringConstants.INT_SIZE +
			// dropped
			MemoryMeasuringConstants.BYTE_SIZE +
			// indexed
			MemoryMeasuringConstants.BYTE_SIZE +
			// key
			MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
			// price id
			MemoryMeasuringConstants.INT_SIZE +
			// price list
			EvitaDataTypes.estimateSize(this.priceKey.priceList()) +
			// currency
			MemoryMeasuringConstants.REFERENCE_SIZE +
			// inner record id
			MemoryMeasuringConstants.REFERENCE_SIZE + ofNullable(this.innerRecordId).map(it -> MemoryMeasuringConstants.INT_SIZE).orElse(0) +
			// price without and with tax + tax
			3 * (MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.BIG_DECIMAL_SIZE) +
			// validity
			MemoryMeasuringConstants.REFERENCE_SIZE + ofNullable(this.validity).stream().mapToInt(EvitaDataTypes::estimateSize).sum();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		Price price = (Price) o;

		if (this.version != price.version) return false;
		return this.priceKey.equals(price.priceKey);
	}

	@Override
	public int hashCode() {
		int result = this.version;
		result = 31 * result + this.priceKey.hashCode();
		return result;
	}

	@Nonnull
	@Override
	public String toString() {
		return (this.dropped ? "❌ " : "") +
			"\uD83D\uDCB0 " + (this.indexed ? "\uD83D\uDCB5 " : "") + this.priceWithTax + " " + this.priceKey.currency() + " (" + this.taxRate + "%)" +
			", price list " + this.priceKey.priceList() +
			(this.validity == null ? "" : ", valid in " + this.validity) +
			", external id " + this.priceKey.priceId() +
			(this.innerRecordId == null ? "" : "/" + this.innerRecordId);
	}

	/**
	 * Primary key of the {@link Price}. Price is uniquely identified by combination: priceId, priceList, currency.
	 *
	 * @param priceId   Contains the identification of the price in the external systems. This ID is expected to be used for
	 *                  synchronization of the price in relation to the primary source of the prices. The price with the same ID
	 *                  must be unique within the same entity. The prices with the same ID in multiple entities should represent
	 *                  the same price in terms of other values - such as validity, currency, price list, the price itself, and all
	 *                  other properties. These values can be different for a limited time (for example, the prices of Entity A and
	 *                  Entity B can be the same, but Entity A is updated in a different session/transaction and at a different time
	 *                  than Entity B).
	 * @param priceList Contains identification of the price list in the external system. Each price must reference a price list. Price list
	 *                  identification may refer to another Evita entity or may contain any external price list identification
	 *                  (for example id or unique name of the price list in the external system).
	 *
	 *                  Single entity is expected to have single price for the price list unless there is {@link #validity()} specified.
	 *                  In other words there is no sense to have multiple concurrently valid prices for the same entity that have roots
	 *                  in the same price list.
	 * @param currency  Identification of the currency. Three-letter form according to [ISO 4217](https://en.wikipedia.org/wiki/ISO_4217).
	 */
	public record PriceKey(
		int priceId,
		@Nonnull String priceList,
		@Nonnull Currency currency

	) implements Serializable, Comparable<PriceKey> {
		@Serial private static final long serialVersionUID = -4115511848409188910L;

		public PriceKey {
			Assert.notNull(priceList, "Price list name is mandatory value!");
			Assert.notNull(currency, "Price currency is mandatory value!");
		}

		@Override
		public int compareTo(PriceKey o) {
			int result = this.currency.getCurrencyCode().compareTo(o.currency.getCurrencyCode());
			if (result == 0) {
				result = this.priceList.compareTo(o.priceList);
				if (result == 0) {
					return Integer.compare(this.priceId, o.priceId);
				} else {
					return result;
				}
			} else {
				return result;
			}
		}

		@Nonnull
		@Override
		public String toString() {
			return "\uD83D\uDCB0 " + this.priceId + " in " + this.priceList + " " + this.currency + " ";
		}
	}

	/**
	 * This comparator sorts {@link PriceKey} by price id first, then by currency and then by price list.
	 * It differs from default comparison logic in {@link PriceKey#compareTo(PriceKey)} that sorts by currency first,
	 * then price list and finally by price id.
	 */
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static class PriceIdFirstPriceKeyComparator implements Comparator<PriceKey>, Serializable {
		public static final PriceIdFirstPriceKeyComparator INSTANCE = new PriceIdFirstPriceKeyComparator();
		@Serial private static final long serialVersionUID = -1011508715822385723L;

		@Override
		public int compare(PriceKey o1, PriceKey o2) {
			int result = Integer.compare(o1.priceId(), o2.priceId());
			if (result == 0) {
				result = o1.currency().getCurrencyCode().compareTo(o2.currency.getCurrencyCode());
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
