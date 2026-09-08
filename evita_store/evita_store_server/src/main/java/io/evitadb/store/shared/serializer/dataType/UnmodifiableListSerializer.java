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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * This serializer implementation stores the unmodifiable view returned by
 * {@link Collections#unmodifiableList(List)}. Writing is fully inherited from {@link ListSerializer} - an
 * unmodifiable view iterates exactly like the list it wraps, so the persisted byte layout is the very same one
 * a mutable list of the same contents produces.
 *
 * Reading cannot be inherited as-is, though. {@link ListSerializer#read(Kryo, Input, Class)} creates the instance
 * from its factory and then populates it item by item, which an unmodifiable view rejects with
 * {@link UnsupportedOperationException}. The factory therefore hands out a plain {@link ArrayList} and the
 * unmodifiable view is put on afterwards, once the list is fully read.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class UnmodifiableListSerializer<K extends Serializable> extends ListSerializer<K> {

	public UnmodifiableListSerializer() {
		super(ArrayList::new);
	}

	@Override
	public List<K> read(Kryo kryo, Input input, Class<? extends List<K>> type) {
		return Collections.unmodifiableList(super.read(kryo, input, type));
	}

}
