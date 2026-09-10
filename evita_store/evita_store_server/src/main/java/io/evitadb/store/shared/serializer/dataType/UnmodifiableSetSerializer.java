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
import java.util.HashSet;
import java.util.Set;

/**
 * This serializer implementation stores the unmodifiable view returned by
 * {@link Collections#unmodifiableSet(Set)}. Writing is fully inherited from {@link SetSerializer} - an unmodifiable
 * view iterates exactly like the set it wraps, so the persisted byte layout is the very same one a mutable set of
 * the same contents produces.
 *
 * Reading cannot be inherited as-is, though. {@link SetSerializer#read(Kryo, Input, Class)} creates the instance
 * from its factory and then populates it item by item, which an unmodifiable view rejects with
 * {@link UnsupportedOperationException}. The factory therefore hands out a plain {@link HashSet} and the
 * unmodifiable view is put on afterwards, once the set is fully read.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class UnmodifiableSetSerializer<T extends Serializable> extends SetSerializer<T> {

	public UnmodifiableSetSerializer() {
		super(HashSet::newHashSet);
	}

	@Override
	public Set<T> read(Kryo kryo, Input input, Class<? extends Set<T>> type) {
		return Collections.unmodifiableSet(super.read(kryo, input, type));
	}

}
