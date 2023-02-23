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

package io.evitadb.dataType;

import io.evitadb.dataType.exception.UnsupportedDataTypeException;
import io.evitadb.utils.Assert;
import lombok.Data;

import javax.annotation.Nonnull;
import java.io.Serial;
import java.io.Serializable;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Multiple data type is targeted for composed comparable attributes that are specifically targeted for sorting purposes.
 * evitaDB requires all data to be sorted up front and there are cases when attribute sorting spans multiple data.
 * For example if you want to sort primary by age and secondary by sex you need to create compose {@link Multiple}
 * attributes that look like this:
 *
 * ``` java
 * entity1.setAttribute("sortKey", new Multiple(78L, "MALE"));
 * entity2.setAttribute("sortKey", new Multiple(189L, "FEMALE"));
 * entity3.setAttribute("sortKey", new Multiple(189L, "MALE"));
 * ```
 *
 * When entities are sorted by sortKey in descending way they will be sorted like this:
 *
 * entity3, entity,2 entity1
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@Data
public final class Multiple implements Comparable<Multiple>, Serializable {
	@Serial private static final long serialVersionUID = -1899832326162360167L;
	public static final String OPENING = "{";
	public static final String CLOSING = "}";
	private final Serializable[] values;

	public <T extends Comparable<? super T> & Serializable, S extends Comparable<? super S> & Serializable> Multiple(T valueA, S valueB) {
		checkValue(valueA);
		checkValue(valueB);
		this.values = new Serializable[] {valueA, valueB};
	}

	public <T extends Comparable<? super T> & Serializable, S extends Comparable<? super S> & Serializable, U extends Comparable<? super U> & Serializable> Multiple(T valueA, S valueB, U valueC) {
		checkValue(valueA);
		checkValue(valueB);
		checkValue(valueC);
		this.values = new Serializable[] {valueA, valueB, valueC};
	}

	public <T extends Comparable<? super T> & Serializable, S extends Comparable<? super S> & Serializable, U extends Comparable<? super U> & Serializable, V extends Comparable<? super V> & Serializable> Multiple(T valueA, S valueB, U valueC, V valueD) {
		checkValue(valueA);
		checkValue(valueB);
		checkValue(valueC);
		checkValue(valueD);
		this.values = new Serializable[] {valueA, valueB, valueC, valueD};
	}

	private static  <T extends Comparable<? super T> & Serializable> void checkValue(T value) {
		Assert.notNull(value, "Multiple cannot hold null value!");
		Assert.isTrue(!(value instanceof Multiple), "Multiple cannot hold another Multiple!");
		Assert.isTrue(
			EvitaDataTypes.isSupportedType(value.getClass()),
			() -> new UnsupportedDataTypeException(value.getClass(), EvitaDataTypes.getSupportedDataTypes())
		);
	}

	@Override
	public String toString() {
		return OPENING + Arrays.stream(values)
			.map(EvitaDataTypes::formatValue)
			.collect(Collectors.joining(",")) + CLOSING;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	@Override
	public int compareTo(@Nonnull Multiple otherMultiple) {
		final int sharedLength = Math.min(values.length, otherMultiple.values.length);
		for(int i = 0; i < sharedLength; i++) {
			final Comparable thisValue = (Comparable) values[i];
			final Comparable otherValue = (Comparable) otherMultiple.values[i];
			final int result;
			if (thisValue.getClass().equals(otherValue.getClass())) {
				result = thisValue.compareTo(otherValue);
			} else {
				result = thisValue.toString().compareTo(otherValue.toString());
			}
			if (result != 0) {
				return result;
			}
		}
		if (values.length == otherMultiple.values.length) {
			// we have a draw
			return 0;
		} else {
			return values.length > otherMultiple.values.length ? 1 : -1;
		}
	}

}
