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
import io.evitadb.api.requestResponse.data.AttributesContract.AttributeKey;
import io.evitadb.dataType.Scope;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalUniqueLeafStreamKey;

/**
 * This {@link Serializer} implementation reads/writes {@link GlobalUniqueLeafStreamKey} — the per-global-unique-index
 * page-stream identity of the granular {@link io.evitadb.index.CatalogIndex} layout — from/to binary format. The scope
 * ordinal is written directly; the attribute identity is delegated to the already-registered {@link AttributeKey}
 * serializer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class GlobalUniqueLeafStreamKeySerializer extends Serializer<GlobalUniqueLeafStreamKey> {

	@Override
	public void write(Kryo kryo, Output output, GlobalUniqueLeafStreamKey globalUniqueLeafStreamKey) {
		output.writeVarInt(globalUniqueLeafStreamKey.getScope().ordinal(), true);
		kryo.writeObject(output, globalUniqueLeafStreamKey.getAttributeKey());
	}

	@Override
	public GlobalUniqueLeafStreamKey read(Kryo kryo, Input input, Class<? extends GlobalUniqueLeafStreamKey> type) {
		final Scope scope = Scope.values()[input.readVarInt(true)];
		final AttributeKey attributeKey = kryo.readObject(input, AttributeKey.class);
		return new GlobalUniqueLeafStreamKey(scope, attributeKey);
	}

}
