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
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeKeyWithIndexType;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.LeafStreamKey.StreamKind;

/**
 * This {@link Serializer} implementation reads/writes {@link LeafStreamKey} — the per-sub-index page-stream identity of
 * the granular FilterIndex layout — from/to binary format. The owning entity index pk is written
 * directly; the attribute/index-type identity is delegated to the already-registered {@link AttributeKeyWithIndexType}
 * serializer (registered earlier in the same {@code CatalogHeaderKryoConfigurer}).
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class LeafStreamKeySerializer extends Serializer<LeafStreamKey> {

	@Override
	public void write(Kryo kryo, Output output, LeafStreamKey leafStreamKey) {
		output.writeVarInt(leafStreamKey.getEntityIndexPrimaryKey(), true);
		kryo.writeObject(output, leafStreamKey.getAttributeKey());
		// the stream kind (BUCKET / RANGE) distinguishes a FilterIndex's value and range page streams
		output.writeVarInt(leafStreamKey.getStreamKind().ordinal(), true);
	}

	@Override
	public LeafStreamKey read(Kryo kryo, Input input, Class<? extends LeafStreamKey> type) {
		final int entityIndexPrimaryKey = input.readVarInt(true);
		final AttributeKeyWithIndexType attributeKey = kryo.readObject(input, AttributeKeyWithIndexType.class);
		final StreamKind streamKind = StreamKind.values()[input.readVarInt(true)];
		return new LeafStreamKey(entityIndexPrimaryKey, attributeKey, streamKind);
	}

}
