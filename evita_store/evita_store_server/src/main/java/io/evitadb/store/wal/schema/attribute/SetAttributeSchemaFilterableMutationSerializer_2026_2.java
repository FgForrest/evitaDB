/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.store.wal.schema.attribute;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.schema.FilterIndexCapability;
import io.evitadb.api.requestResponse.schema.mutation.attribute.SetAttributeSchemaFilterableMutation;
import io.evitadb.store.wal.schema.MutationSerializationFunctions;

/**
 * Reads {@link SetAttributeSchemaFilterableMutation} from the WAL format shipped by release 2026.2 - the shape that
 * predates the per-scope {@link FilterIndexCapability filter index capabilities}. Such a record ends with the
 * filterable scope array and carries no capability section, not even its presence flag.
 *
 * The mutation is therefore reconstructed with `null` capabilities, which it normalizes into the empty array - exactly
 * the behaviour every `filterable()` call had before capabilities existed.
 *
 * This serializer only reads - writes always go through the current
 * {@link SetAttributeSchemaFilterableMutationSerializer}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 * @deprecated kept for backward compatibility; can be removed once no WAL written before filter index capabilities
 *             were introduced is still replayed.
 */
@Deprecated(since = "2026.2", forRemoval = true)
public class SetAttributeSchemaFilterableMutationSerializer_2026_2
	extends Serializer<SetAttributeSchemaFilterableMutation> implements MutationSerializationFunctions {

	@Override
	public void write(Kryo kryo, Output output, SetAttributeSchemaFilterableMutation mutation) {
		throw new UnsupportedOperationException("This serializer is deprecated and should not be used.");
	}

	@Override
	public SetAttributeSchemaFilterableMutation read(
		Kryo kryo,
		Input input,
		Class<? extends SetAttributeSchemaFilterableMutation> type
	) {
		return new SetAttributeSchemaFilterableMutation(
			input.readString(),
			readScopeArray(kryo, input)
		);
	}

}
