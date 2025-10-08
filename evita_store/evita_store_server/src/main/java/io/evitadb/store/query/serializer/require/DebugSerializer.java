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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.Debug;
import io.evitadb.api.query.require.DebugMode;
import lombok.RequiredArgsConstructor;

import java.io.Serializable;

/**
 * This {@link Serializer} implementation reads/writes {@link Debug} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class DebugSerializer extends Serializer<Debug> {

	@Override
	public void write(Kryo kryo, Output output, Debug object) {
		final Serializable[] modes = object.getArguments();
		output.writeVarInt(modes.length, true);
		for (Serializable mode : modes) {
			kryo.writeObject(output, mode);
		}
	}

	@Override
	public Debug read(Kryo kryo, Input input, Class<? extends Debug> type) {
		final DebugMode[] modes = new DebugMode[input.readVarInt(true)];
		for (int i = 0; i < modes.length; i++) {
			modes[i] = kryo.readObject(input, DebugMode.class);
		}
		return new Debug(modes);
	}

}
