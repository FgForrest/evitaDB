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

package io.evitadb.utils;

import io.evitadb.dataType.EvitaDataTypes;

import javax.annotation.Nonnull;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * This interface contains usual memory sizes (in Bytes) for base Java types. The data were taken from
 * <a href="https://softwareengineering.stackexchange.com/questions/162546/why-the-overhead-when-allocating-objects-arrays-in-java">StackExchange</a>
 * post and are valid for 64-bit architecture.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface MemoryMeasuringConstants {
	int OBJECT_HEADER_SIZE = 16;
	int REFERENCE_SIZE = 8;
	int ARRAY_BASE_SIZE = OBJECT_HEADER_SIZE + 8;
	int BYTE_SIZE = 1;
	int CHAR_SIZE = 2;
	int SMALL_SIZE = 2;
	int INT_SIZE = 4;
	int LONG_SIZE = 8;
	/**
	 * Source: <a href="https://stackoverflow.com/questions/2501176/java-bigdecimal-memory-usage">with estimate for a large magnitude</a>.
	 */
	int BIG_INTEGER = 20 + OBJECT_HEADER_SIZE + 4;
	int BIG_DECIMAL_SIZE = 16 + BIG_INTEGER;
	/**
	 * Source: <a href="https://www.javamex.com/tutorials/memory/">of the data</a>
	 */
	int LOCAL_DATE_TIME_SIZE = 48;
	int LOCAL_DATE_SIZE = 24;
	int LOCAL_TIME_SIZE = 24;

	/**
	 * Source <a href="https://www.javamex.com/tutorials/memory/string_memory_usage.shtml">of computational logic</a>.
	 */
	static int computeStringSize(@Nonnull String string) {
		return 8 * ((((string.length()) * 2) + 45) / 8);
	}

	/**
	 * Source: <a href="https://medium.com/metrosystemsro/java-memory-footprint-part-2-8791679178e2">for the base size</a>
	 * and <a href="https://github.com/DimitrisAndreou/memory-measurer/blob/master/ElementCostInDataStructures.txt">for element size</a>.
	 */
	static int computeLinkedListSize(@Nonnull List<Serializable> list) {
		return 48 + 24 * list.size() +
			list.stream()
				.mapToInt(EvitaDataTypes::estimateSize)
				.sum();
	}

	/**
	 * Computes size of the array.
	 */
	static int computeArraySize(@Nonnull Serializable[] array) {
		return MemoryMeasuringConstants.ARRAY_BASE_SIZE + array.length * MemoryMeasuringConstants.REFERENCE_SIZE +
			Arrays.stream(array)
				.mapToInt(EvitaDataTypes::estimateSize)
				.sum();
	}

	/**
	 * Computes size of the array.
	 */
	static int computeArraySize(@Nonnull int[] array) {
		return MemoryMeasuringConstants.ARRAY_BASE_SIZE + array.length * MemoryMeasuringConstants.REFERENCE_SIZE +
			array.length * MemoryMeasuringConstants.INT_SIZE;
	}

	/**
	 * Source: <a href="https://medium.com/metrosystemsro/java-memory-footprint-part-2-8791679178e2">for the base size</a>
	 * and <a href="https://github.com/DimitrisAndreou/memory-measurer/blob/master/ElementCostInDataStructures.txt">for element size</a>.
	 */
	static int computeHashMapSize(@Nonnull Map<? extends Serializable, ? extends Serializable> map) {
		return 128 + 32 * map.size() +
			map.entrySet()
				.stream()
				.mapToInt(it -> EvitaDataTypes.estimateSize(it.getKey()) + EvitaDataTypes.estimateSize(it.getValue()))
				.sum();
	}

	/**
	 * Returns estimated size per component.
	 */
	static int getElementSize(Class<?> componentType) {
		if (byte.class.isInstance(componentType)) {
			return BYTE_SIZE;
		} else if (short.class.isInstance(componentType)) {
			return SMALL_SIZE;
		} else if (int.class.isInstance(componentType)) {
			return INT_SIZE;
		} else if (long.class.isInstance(componentType)) {
			return LONG_SIZE;
		} else if (char.class.isInstance(componentType)) {
			return CHAR_SIZE;
		} else {
			return REFERENCE_SIZE;
		}
	}

}
