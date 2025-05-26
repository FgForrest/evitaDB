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

package io.evitadb.store.dataType.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.serializers.ImmutableSerializer;
import io.evitadb.dataType.ReferencedEntityPredecessor;

import javax.annotation.Nullable;

/**
 * This {@link Serializer} implementation reads/writes ReferencedEntityPredecessor type.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public class ReferencedEntityPredecessorSerializer extends ImmutableSerializer<ReferencedEntityPredecessor> {
	{
		setAcceptsNull(true);
	}

	@Override
	public void write (Kryo kryo, Output output, ReferencedEntityPredecessor object) {
		if (object == null) {
			output.writeBoolean(false);
		} else {
			output.writeBoolean(true);
			output.writeInt(object.predecessorPk());
		}
	}

	@Nullable
	@Override
	public ReferencedEntityPredecessor read (Kryo kryo, Input input, Class<? extends ReferencedEntityPredecessor> type) {
		if (input.readBoolean()) {
			return new ReferencedEntityPredecessor(input.readInt());
		} else {
			return null;
		}
	}
}
