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
import io.evitadb.api.requestResponse.schema.analysis.AnalysisStep;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.GlobalAttributeNormalizationStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readAnalysisSteps;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.writeAnalysisSteps;

/**
 * Serializes authoritative catalog normalization rows as ordered step descriptions.
 * Global attribute names and step parameter names are written deterministically. Empty chains are represented by
 * absence, matching the catalog's type-level NFD-only policy without persisting executable functions.
 */
public class GlobalAttributeNormalizationStoragePartSerializer
	extends Serializer<GlobalAttributeNormalizationStoragePart> {
	@Override
	public void write(
		@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull GlobalAttributeNormalizationStoragePart part
	) {
		output.writeVarInt(part.rows().size(), true);
		new TreeMap<>(part.rows()).forEach((name, steps) -> {
			output.writeString(name);
			writeAnalysisSteps(kryo, output, steps);
		});
	}

	@Override
	@Nonnull
	public GlobalAttributeNormalizationStoragePart read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends GlobalAttributeNormalizationStoragePart> type
	) {
		final int count = input.readVarInt(true);
		final Map<String, List<AnalysisStep>> rows = CollectionUtils.createHashMap(count);
		for (int i = 0; i < count; i++) {
			rows.put(input.readString(), readAnalysisSteps(kryo, input));
		}
		return new GlobalAttributeNormalizationStoragePart(Map.copyOf(rows));
	}
}
