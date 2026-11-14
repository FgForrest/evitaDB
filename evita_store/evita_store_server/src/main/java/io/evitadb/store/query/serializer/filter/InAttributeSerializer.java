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

package io.evitadb.store.query.serializer.filter;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.filter.InAttribute;

import javax.annotation.Nonnull;

/**
 * This {@link Serializer} implementation reads/writes {@link InAttribute} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public class InAttributeSerializer extends Serializer<InAttribute> {

	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull InAttribute inAttribute) {
		final String[] names = inAttribute.getAttributeNames();
		output.writeVarInt(names.length, true);
		for (final String name : names) {
			output.writeString(name);
		}
	}

	@Nonnull
	@Override
	public InAttribute read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends InAttribute> type
	) {
		final String[] names = new String[input.readVarInt(true)];
		for (int i = 0; i < names.length; i++) {
			names[i] = input.readString();
		}
		return new InAttribute(names);
	}

}
