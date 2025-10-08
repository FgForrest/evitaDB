/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.store.index.serializer;


import com.esotericsoftware.kryo.io.Input;
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.exception.GenericEvitaInternalError;
import io.evitadb.store.service.KeyCompressor;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexKey;

import javax.annotation.Nonnull;

/**
 * This interface exists only to keep the backward compatibility with the serialized data that might contain
 * {@link AttributeKey} in places where now {@link AttributeIndexKey} is expected.
 *
 * @deprecated temporal utility for migration purposes
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Deprecated(since = "2025.5", forRemoval = true)
public interface AttributeKeyToAttributeKeyIndexBridge {
	/**
	 * Constructs an {@link AttributeIndexKey} instance by deserializing data using the provided {@link Input} and
	 * decompressing the key using the given {@link KeyCompressor}.
	 *
	 * @param input the input from which the serialized data is read
	 * @param keyCompressor the {@link KeyCompressor} instance responsible for decompressing the key
	 * @return the reconstructed {@link AttributeIndexKey} instance
	 * @throws GenericEvitaInternalError if the decompressed key is not of type {@link AttributeIndexKey}
	 *                                   or {@link AttributeKey}
	 * @deprecated this method should be removed when we can be sure there is no serialized data in the wild that
	 * 		       AttributeKey in stored indexes, new correct data type is AttributeIndexKey
	 */
	@Deprecated(since = "2025.5", forRemoval = true)
	@Nonnull
	default AttributeIndexKey getAttributeIndexKey(@Nonnull Input input, @Nonnull KeyCompressor keyCompressor) {
		final Object keyForId = keyCompressor.getKeyForId(input.readVarInt(true));
		final AttributeIndexKey attributeIndexKey;
		if (keyForId instanceof AttributeIndexKey aik) {
			attributeIndexKey = aik;
		} else if (keyForId instanceof AttributeKey ak) {
			attributeIndexKey = new AttributeIndexKey(null, ak.attributeName(), ak.locale());
		} else {
			throw new GenericEvitaInternalError("Expected AttributeIndexKey or AttributeKey but " + keyForId.getClass() + " was found!");
		}
		return attributeIndexKey;
	}
}
