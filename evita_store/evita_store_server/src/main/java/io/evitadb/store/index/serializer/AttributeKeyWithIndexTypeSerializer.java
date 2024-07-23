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

package io.evitadb.store.index.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.store.spi.model.storageParts.index.AttributeIndexStoragePart.AttributeIndexType;
import io.evitadb.store.spi.model.storageParts.index.AttributeKeyWithIndexType;

import java.util.Locale;

/**
 * This {@link Serializer} implementation reads/writes {@link AttributeKeyWithIndexType} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class AttributeKeyWithIndexTypeSerializer extends Serializer<AttributeKeyWithIndexType> {

	@Override
	public void write(Kryo kryo, Output output, AttributeKeyWithIndexType attributeKeyWithIndexType) {
		output.writeString(attributeKeyWithIndexType.getAttributeName());
		kryo.writeObjectOrNull(output, attributeKeyWithIndexType.getLocale(), Locale.class);
		output.writeString(attributeKeyWithIndexType.getIndexType().name());
	}

	@Override
	public AttributeKeyWithIndexType read(Kryo kryo, Input input, Class<? extends AttributeKeyWithIndexType> type) {
		final String attributeName = input.readString();
		final Locale locale = kryo.readObjectOrNull(input, Locale.class);
		final AttributeIndexType indexType = AttributeIndexType.valueOf(input.readString());
		return new AttributeKeyWithIndexType(attributeName, locale, indexType);
	}

}
