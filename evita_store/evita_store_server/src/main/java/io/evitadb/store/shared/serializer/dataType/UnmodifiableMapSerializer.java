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

package io.evitadb.store.shared.serializer.dataType;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.io.Input;

import java.io.Serializable;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * This serializer implementation stores the unmodifiable view returned by
 * {@link Collections#unmodifiableMap(Map)}. Writing is fully inherited from {@link MapSerializer} - an unmodifiable
 * view iterates exactly like the map it wraps, so the persisted byte layout is the very same one a mutable map of
 * the same contents produces.
 *
 * Reading cannot be inherited as-is, though. {@link MapSerializer#read(Kryo, Input, Class)} creates the instance
 * from its factory and then populates it entry by entry, which an unmodifiable view rejects with
 * {@link UnsupportedOperationException}. The factory therefore hands out a plain {@link HashMap} and the
 * unmodifiable view is put on afterwards, once the map is fully read.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class UnmodifiableMapSerializer<K extends Serializable, V extends Serializable> extends MapSerializer<K, V> {

	public UnmodifiableMapSerializer() {
		super(HashMap::newHashMap);
	}

	@Override
	public Map<K, V> read(Kryo kryo, Input input, Class<? extends Map<K, V>> type) {
		return Collections.unmodifiableMap(super.read(kryo, input, type));
	}

}
