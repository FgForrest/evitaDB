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
import io.evitadb.index.analysis.AttributeNormalizationPolicy.ElementBinding;
import io.evitadb.index.analysis.AttributeNormalizationPolicy;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeIndexKey;
import io.evitadb.spi.store.catalog.persistence.storageParts.index.AttributeNormalizationStoragePart;
import io.evitadb.utils.CollectionUtils;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.evitadb.store.wal.schema.MutationSerializationFunctions.readAnalysisSteps;
import static io.evitadb.store.wal.schema.MutationSerializationFunctions.writeAnalysisSteps;

/**
 * Serializes a collection's built-with rows, retained compound bindings and global adoption identities.
 * Attribute identities and parameter names are written deterministically. The singleton has its own registration,
 * so its description-only payload does not alter the layouts of existing value-index storage parts.
 */
public class AttributeNormalizationStoragePartSerializer extends Serializer<AttributeNormalizationStoragePart> {
	@Override
	public void write(@Nonnull Kryo kryo, @Nonnull Output output, @Nonnull AttributeNormalizationStoragePart part) {
		final AttributeNormalizationPolicy policy = part.policy();
		output.writeVarInt(policy.rows().size(), true);
		new TreeMap<>(policy.rows()).forEach((key, steps) -> {
			writeKey(output, key);
			writeAnalysisSteps(kryo, output, steps);
		});
		output.writeVarInt(policy.compounds().size(), true);
		new TreeMap<>(policy.compounds()).forEach((key, elements) -> {
			writeKey(output, key);
			output.writeVarInt(elements.size(), true);
			for (final ElementBinding element : elements) {
				output.writeString(element.attributeName());
				kryo.writeClass(output, element.type());
				output.writeBoolean(element.localized());
				output.writeBoolean(element.global());
			}
		});
		output.writeVarInt(policy.globalAttributes().size(), true);
		for (final String name : new TreeSet<>(policy.globalAttributes())) {
			output.writeString(name);
		}
	}

	@Override
	@Nonnull
	public AttributeNormalizationStoragePart read(
		@Nonnull Kryo kryo, @Nonnull Input input, @Nonnull Class<? extends AttributeNormalizationStoragePart> type
	) {
		final int rowCount = input.readVarInt(true);
		final Map<AttributeIndexKey, List<AnalysisStep>> rows = CollectionUtils.createHashMap(rowCount);
		for (int i = 0; i < rowCount; i++) {
			rows.put(readKey(input), readAnalysisSteps(kryo, input));
		}
		final int compoundCount = input.readVarInt(true);
		final Map<AttributeIndexKey, List<ElementBinding>> compounds = CollectionUtils.createHashMap(compoundCount);
		for (int i = 0; i < compoundCount; i++) {
			final AttributeIndexKey key = readKey(input);
			final int size = input.readVarInt(true);
			final List<ElementBinding> elements = new ArrayList<>(size);
			for (int j = 0; j < size; j++) {
				elements.add(new ElementBinding(input.readString(), kryo.readClass(input).getType(),
					input.readBoolean(), input.readBoolean()));
			}
			compounds.put(key, List.copyOf(elements));
		}
		final int globalCount = input.readVarInt(true);
		final Set<String> globals = CollectionUtils.createHashSet(globalCount);
		for (int i = 0; i < globalCount; i++) {
			globals.add(input.readString());
		}
		return new AttributeNormalizationStoragePart(new AttributeNormalizationPolicy(rows, compounds, globals));
	}

	/**
	 * Writes the scope- and locale-independent identity.
	 *
	 * @param output destination of the serialized identity
	 * @param key    attribute identity; only its reference name and attribute name are written
	 */
	private static void writeKey(@Nonnull Output output, @Nonnull AttributeIndexKey key) {
		output.writeString(key.referenceName());
		output.writeString(key.attributeName());
	}

	/**
	 * Reads the scope- and locale-independent identity.
	 *
	 * @param input source positioned at the identity written by {@link #writeKey}
	 * @return identity without scope and locale
	 */
	@Nonnull
	private static AttributeIndexKey readKey(@Nonnull Input input) {
		return new AttributeIndexKey(input.readString(), input.readString(), null);
	}
}
