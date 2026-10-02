/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.FulltextLeafStreamKey.StreamKind;

import java.util.Locale;

/**
 * This {@link Serializer} implementation reads/writes {@link FulltextLeafStreamKey} - the identity of one page stream
 * of a fulltext index - from/to binary format. The stream kind is written by name, for stability against a future
 * reordering of the enum.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class FulltextLeafStreamKeySerializer extends Serializer<FulltextLeafStreamKey> {

	@Override
	public void write(Kryo kryo, Output output, FulltextLeafStreamKey streamKey) {
		output.writeVarInt(streamKey.entityIndexPrimaryKey(), false);
		kryo.writeObject(output, streamKey.locale());
		output.writeString(streamKey.streamKind().name());
	}

	@Override
	public FulltextLeafStreamKey read(Kryo kryo, Input input, Class<? extends FulltextLeafStreamKey> type) {
		final int entityIndexPrimaryKey = input.readVarInt(false);
		final Locale locale = kryo.readObject(input, Locale.class);
		final StreamKind streamKind = StreamKind.valueOf(input.readString());
		return new FulltextLeafStreamKey(entityIndexPrimaryKey, locale, streamKind);
	}

}
