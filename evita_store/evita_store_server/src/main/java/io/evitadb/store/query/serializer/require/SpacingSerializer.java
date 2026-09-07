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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.Spacing;
import io.evitadb.api.query.require.SpacingGap;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link Spacing} from/to binary format.
 *
 * The payload is the number of gap rules followed by the rules themselves. `Spacing` accepts nothing but
 * {@link SpacingGap} children, so the gaps are written through their own registered serializer rather than with a
 * class marker each.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@RequiredArgsConstructor
public class SpacingSerializer extends Serializer<Spacing> {

	@Override
	public void write(Kryo kryo, Output output, Spacing object) {
		final SpacingGap[] gaps = object.getGaps();
		output.writeVarInt(gaps.length, true);
		for (SpacingGap gap : gaps) {
			kryo.writeObject(output, gap);
		}
	}

	@Override
	public Spacing read(Kryo kryo, Input input, Class<? extends Spacing> type) {
		final SpacingGap[] gaps = new SpacingGap[input.readVarInt(true)];
		for (int i = 0; i < gaps.length; i++) {
			gaps[i] = kryo.readObject(input, SpacingGap.class);
		}
		return new Spacing(gaps);
	}

}
