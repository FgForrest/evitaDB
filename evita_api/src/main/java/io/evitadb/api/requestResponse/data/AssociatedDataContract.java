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

package io.evitadb.api.requestResponse.data;

import io.evitadb.api.query.QueryUtils;
import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.dataType.EvitaDataTypes;
import io.evitadb.dataType.exception.IncompleteDeserializationException;
import io.evitadb.exception.EvitaInvalidUsageException;
import io.evitadb.utils.Assert;
import io.evitadb.utils.MemoryMeasuringConstants;
import io.evitadb.utils.ReflectionLookup;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static io.evitadb.utils.ComparatorUtils.compareLocale;

/**
 * This interface prescribes a set of methods that must be implemented by the object, that maintains set of associated data.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface AssociatedDataContract extends Serializable, AssociatedDataAvailabilityChecker {

	/**
	 * Returns true if single associated data differs between first and second instance.
	 */
	static boolean anyAssociatedDataDifferBetween(AssociatedDataContract first, AssociatedDataContract second) {
		final Collection<AssociatedDataValue> thisValues = first.associatedDataAvailable() ? first.getAssociatedDataValues() : Collections.emptyList();
		final Collection<AssociatedDataValue> otherValues = second.associatedDataAvailable() ? second.getAssociatedDataValues() : Collections.emptyList();

		if (thisValues.size() != otherValues.size()) {
			return true;
		} else {
			return thisValues
				.stream()
				.anyMatch(it -> {
					final AssociatedDataKey key = it.key();
					final Serializable thisValue = it.value();
					final Serializable otherValue = key.locale() == null ?
						second.getAssociatedData(key.associatedDataName()) :
						second.getAssociatedData(key.associatedDataName(), key.localeOrThrowException());
					return QueryUtils.valueDiffers(thisValue, otherValue);
				});
		}
	}

	/**
	 * Returns value associated with the key or null when the associatedData is missing.
	 *
	 * @throws ClassCastException when associatedData is of different type than expected
	 */
	@Nullable
	<T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName);

	/**
	 * Returns value associated with the key or null when the associatedData is missing.
	 *
	 * @throws ClassCastException                 when associatedData is of different type than expected
	 * @throws IncompleteDeserializationException when only part of the complex object data was deserialized
	 */
	@Nullable
	<T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup);

	/**
	 * Returns array of values associated with the key or null when the associatedData is missing.
	 *
	 * @throws ClassCastException when associatedData is of different type than expected or is not an array
	 */
	@Nullable
	<T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName);

	/**
	 * Returns array of values associated with the key or null when the associated data is missing.
	 *
	 * Method returns wrapper dto for the associated data that contains information about the associated data version
	 * and state.
	 */
	@Nonnull
	Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName);

	/**
	 * Returns value associated with the key or null when the associatedData is missing.
	 * When localized associatedData is not found it is looked up in generic (non-localized) associatedDatas. This makes this
	 * method safest way how to lookup for associatedData if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException when associatedData is of different type than expected
	 */
	@Nullable
	<T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale);

	/**
	 * Returns value associated with the key or null when the associatedData is missing.
	 *
	 * @throws ClassCastException                 when associatedData is of different type than expected
	 * @throws IncompleteDeserializationException when only part of the complex object data was deserialized
	 */
	@Nullable
	<T extends Serializable> T getAssociatedData(@Nonnull String associatedDataName, @Nonnull Locale locale, @Nonnull Class<T> dtoType, @Nonnull ReflectionLookup reflectionLookup);

	/**
	 * Returns array of values associated with the key or null when the associatedData is missing.
	 * When localized associatedData is not found it is looked up in generic (non-localized) associatedDatas. This makes this
	 * method safest way how to lookup for associatedData if caller doesn't know whether it is localized or not.
	 *
	 * @throws ClassCastException when associatedData is of different type than expected or is not an array
	 */
	@Nullable
	<T extends Serializable> T[] getAssociatedDataArray(@Nonnull String associatedDataName, @Nonnull Locale locale);

	/**
	 * Returns array of values associated with the key or null when the associated data is missing.
	 * When localized associated data is not found it is looked up in generic (non-localized) associated data. This
	 * makes this method safest way how to lookup for associated data if caller doesn't know whether it is localized
	 * or not.
	 *
	 * Method returns wrapper dto for the associated data that contains information about the associated data version
	 * and state.
	 */
	@Nonnull
	Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull String associatedDataName, @Nonnull Locale locale);

	/**
	 * Returns definition for the associatedData of specified name.
	 */
	@Nonnull
	Optional<AssociatedDataSchemaContract> getAssociatedDataSchema(@Nonnull String associatedDataName);

	/**
	 * Returns set of all keys registered in this associatedData set. The result set is not limited to the set
	 * of currently fetched associated data.
	 */
	@Nonnull
	Set<String> getAssociatedDataNames();

	/**
	 * Returns set of all keys (combination of associated data name and locale) registered in this associated data.
	 */
	@Nonnull
	Set<AssociatedDataKey> getAssociatedDataKeys();

	/**
	 * Returns array of values associated with the key or null when the associated data is missing.
	 * When localized associated data is not found it is looked up in generic (non-localized) associated data.
	 * This makes this method the safest way how to lookup for associated data if caller doesn't know whether it is
	 * localized or not.
	 *
	 * Method returns wrapper dto for the associated data that contains information about the associated data version
	 * and state.
	 */
	@Nonnull
	Optional<AssociatedDataValue> getAssociatedDataValue(@Nonnull AssociatedDataKey associatedDataKey);

	/**
	 * Returns collection of all values present in this object.
	 */
	@Nonnull
	Collection<AssociatedDataValue> getAssociatedDataValues();

	/**
	 * Returns collection of all values of `associatedDataName` present in this object. This method has usually sense
	 * only when the associated data is present in multiple localizations.
	 */
	@Nonnull
	Collection<AssociatedDataValue> getAssociatedDataValues(@Nonnull String associatedDataName);

	/**
	 * Method returns set of locales used in the localized associated data. The result set is not limited to the set
	 * of currently fetched associated data.
	 */
	@Nonnull
	Set<Locale> getAssociatedDataLocales();

	/**
	 * Inner implementation used in {@link AssociatedDataContract} to represent a proper key in hash map.
	 *
	 * @param associatedDataName Unique name of the associatedData. Case-sensitive. Distinguishes one associated data
	 *                           item from another within single entity instance.
	 * @param locale             Contains locale in case the associatedData is locale specific
	 *                           (i.e. {@link AssociatedDataSchemaContract#isLocalized()}
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	record AssociatedDataKey(
		@Nonnull String associatedDataName,
		@Nullable Locale locale
	) implements Serializable, Comparable<AssociatedDataKey> {
		@Serial private static final long serialVersionUID = -8516513307116598241L;

		/**
		 * Constructor for the locale specific associatedData.
		 */
		public AssociatedDataKey {
			Assert.notNull(associatedDataName, "AssociatedData name cannot be null!");
		}

		/**
		 * Construction for the locale agnostics associatedData.
		 */
		public AssociatedDataKey(@Nonnull String associatedDataName) {
			this(associatedDataName, null);
		}

		/**
		 * Returns true if associatedData is localized.
		 */
		public boolean localized() {
			return this.locale != null;
		}

		/**
		 * Retrieves the locale associated with the instance.
		 * Throws an exception if the locale is not present.
		 *
		 * @return the associated Locale
		 * @throws EvitaInvalidUsageException if the locale is not present
		 */
		@Nonnull
		public Locale localeOrThrowException() {
			Assert.isTrue(
				this.locale != null,
				"Associated data key " + this.associatedDataName + " is not accompanied by locale identifier!"
			);
			return this.locale;
		}

		@Override
		public int compareTo(@Nonnull AssociatedDataKey o) {
			return compareLocale(this.locale, o.locale, () -> this.associatedDataName.compareTo(o.associatedDataName));
		}

		/**
		 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
		 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
		 */
		public int estimateSize() {
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE +
				// data name
				MemoryMeasuringConstants.REFERENCE_SIZE + MemoryMeasuringConstants.computeStringSize(this.associatedDataName) +
				// locale
				MemoryMeasuringConstants.REFERENCE_SIZE;
		}

		@Nonnull
		@Override
		public String toString() {
			return this.associatedDataName + (this.locale == null ? "" : ":" + this.locale);
		}
	}

	/**
	 * Associated data holder is used internally to keep track of associated data as well as its unique identification that
	 * is assigned new everytime associated data changes somehow.
	 *
	 * @param version Contains version of this object and gets increased with any associatedData update. Allows to execute
	 *                optimistic locking i.e. avoiding parallel modifications.
	 * @param key     Uniquely identifies the associated data value among other associated data in the same entity instance.
	 * @param value   Returns associated data value contents.
	 * @param dropped Contains TRUE if associatedData was dropped - i.e. removed. Such associated data are not removed
	 *                (unless tidying process does it), but are lying among other associated data with tombstone flag.
	 *                Dropped associated data can be overwritten by a new value continuing with the versioning where it
	 *                was stopped for the last time.
	 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
	 */
	record AssociatedDataValue(
		int version,
		@Nonnull AssociatedDataKey key,
		@Nullable Serializable value,
		boolean dropped
	) implements Versioned, Droppable, Serializable, ContentComparator<AssociatedDataValue> {
		@Serial private static final long serialVersionUID = -4732226749198696974L;

		public AssociatedDataValue(@Nonnull AssociatedDataKey key, @Nullable Serializable value) {
			this(1, key, value, false);
		}

		public AssociatedDataValue(int version, @Nonnull AssociatedDataKey key, @Nullable Serializable value) {
			this(version, key, value, false);
		}

		/**
		 * Retrieves the value associated with this instance if it is not null.
		 * If the value is null, an {@link EvitaInvalidUsageException} is thrown.
		 *
		 * @return the associated value as a {@link Serializable} object
		 * @throws EvitaInvalidUsageException if the value is null
		 */
		@Nonnull
		public Serializable valueOrThrowException() {
			Assert.isTrue(
				this.value != null,
				"Associated data value for key " + this.key.associatedDataName() + " is null!"
			);
			return this.value;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			AssociatedDataValue that = (AssociatedDataValue) o;

			if (this.version != that.version) return false;
			return this.key.equals(that.key);
		}

		@Override
		public int hashCode() {
			int result = this.version;
			result = 31 * result + this.key.hashCode();
			return result;
		}

		/**
		 * Method returns gross estimation of the in-memory size of this instance. The estimation is expected not to be
		 * a precise one. Please use constants from {@link MemoryMeasuringConstants} for size computation.
		 */
		public int estimateSize() {
			// version
			// dropped
			// key
			// value size estimate
			return MemoryMeasuringConstants.OBJECT_HEADER_SIZE
				// version
				+ MemoryMeasuringConstants.INT_SIZE +
				// dropped
				+MemoryMeasuringConstants.BYTE_SIZE +
				// key
				+this.key.estimateSize()
				// value size estimate
				+ MemoryMeasuringConstants.REFERENCE_SIZE + EvitaDataTypes.estimateSize(this.value);
		}

		/**
		 * Returns true if this associatedData differs in key factors from the passed associatedData.
		 */
		@Override
		public boolean differsFrom(@Nullable AssociatedDataValue otherAssociatedDataValue) {
			if (otherAssociatedDataValue == null) return true;
			if (!Objects.equals(this.key, otherAssociatedDataValue.key)) return true;
			if (QueryUtils.valueDiffers(this.value, otherAssociatedDataValue.value)) return true;
			return this.dropped != otherAssociatedDataValue.dropped;
		}

		@Nonnull
		@Override
		public String toString() {
			return (this.dropped ? "❌ " : "") +
				"\uD83D\uDD11 " + this.key.associatedDataName() + " " +
				(this.key.locale() == null ? "" : "(" + this.key.locale() + ")") +
				": " +
				(
					this.value instanceof Object[] ?
						"[" + Arrays.stream((Object[]) this.value).filter(Objects::nonNull).map(Object::toString).collect(Collectors.joining(",")) + "]" :
						this.value
				);
		}
	}
}
