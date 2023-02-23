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

package io.evitadb.store.service;

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.api.requestResponse.data.structure.Price;
import io.evitadb.api.requestResponse.data.structure.Price.PriceKey;
import io.evitadb.store.exception.CompressionKeyUnknownException;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serializable;
import java.util.Map;

import static io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;

/**
 * Implementations of this interface allows to translate complex keys that are repeated in entities to int values and
 * thus allow big savings of space in serialized form. This is easiest form of compression that can yield significant
 * results.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public interface KeyCompressor extends Serializable {

	/**
	 * Returns index of all collected keys and their assigned ids.
	 */
	@Nonnull
	Map<Integer, Object> getKeys();

	/**
	 * Returns internal ID that can be used instead of storing complex `key` object during serialization in Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributeKey},
	 * {@link AssociatedDataKey} or {@link PriceKey}
	 */
	<T extends Comparable<T>> int getId(@Nonnull T key) throws CompressionKeyUnknownException;

	/**
	 * Returns internal ID that can be used instead of storing complex `key` object during serialization in Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributeKey},
	 * {@link AssociatedDataKey} or {@link PriceKey}
	 *
	 * Method may return null when no id exists yet and the implementation cannot generate new id.
	 */
	@Nullable
	<T extends Comparable<T>> Integer getIdIfExists(@Nonnull T key);

	/**
	 * Returns original `key` that is linked to passed integer id that was acquired during deserialization from Kryo.
	 * This is used to minimize size of the serialized data by limiting size of keys that are massively duplicated
	 * thorough the data base. Keys are usually {@link AttributesContract.AttributeKey},
	 * {@link AssociatedDataKey} or {@link Price.PriceKey}
	 */
	@Nonnull
	<T extends Comparable<T>> T getKeyForId(int id);
}
