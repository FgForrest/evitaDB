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

package io.evitadb.store.entity.serializer;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.requestResponse.data.AssociatedDataContract.AssociatedDataKey;
import io.evitadb.dataType.Scope;
import io.evitadb.store.entity.model.entity.EntityBodyStoragePart;
import io.evitadb.store.service.KeyCompressor;
import lombok.RequiredArgsConstructor;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * This {@link Serializer} implementation reads/writes {@link EntityBodyStoragePart} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@RequiredArgsConstructor
public class EntityBodyStoragePartSerializer_2024_11 extends Serializer<EntityBodyStoragePart> {
	private final KeyCompressor keyCompressor;

	@Override
	public void write(Kryo kryo, Output output, EntityBodyStoragePart object) {
		output.writeVarInt(object.getVersion(), true);
		output.writeInt(object.getPrimaryKey());
		kryo.writeObjectOrNull(output, object.getParent(), Integer.class);

		final Set<Locale> locales = object.getLocales();
		output.writeVarInt(locales.size(), true);
		// the locales are always sorted to ensure the same order of attributes in the serialized form
		locales.stream().sorted(Comparator.comparing(Locale::toLanguageTag)).forEach(locale -> kryo.writeObject(output, locale));

		final Set<Locale> attributeLocales = object.getAttributeLocales();
		output.writeVarInt(attributeLocales.size(), true);
		// the attribute locales are always sorted to ensure the same order of attributes in the serialized form
		attributeLocales.stream().sorted(Comparator.comparing(Locale::toLanguageTag)).forEach(locale -> kryo.writeObject(output, locale));

		final Set<AssociatedDataKey> associatedDataKeys = object.getAssociatedDataKeys();
		output.writeVarInt(associatedDataKeys.size(), true);
		// the associated data are always sorted to ensure the same order of attributes in the serialized form
		// the attribute locales are always sorted to ensure the same order of attributes in the serialized form
		associatedDataKeys.stream().sorted()
			.forEach(associatedDataKey -> output.writeVarInt(this.keyCompressor.getId(associatedDataKey), true));
	}

	@Override
	public EntityBodyStoragePart read(Kryo kryo, Input input, Class<? extends EntityBodyStoragePart> type) {
		final long totalBefore = input.total();

		final int version = input.readVarInt(true);
		final int entityPrimaryKey = input.readInt();
		final Integer hierarchicalPlacement = kryo.readObjectOrNull(input, Integer.class);

		final int localeCount = input.readVarInt(true);
		final Set<Locale> locales = new LinkedHashSet<>(localeCount);
		for (int i = 0; i < localeCount; i++) {
			locales.add(kryo.readObject(input, Locale.class));
		}

		final int attributeLocalesCount = input.readVarInt(true);
		final Set<Locale> attributeLocales = new LinkedHashSet<>(attributeLocalesCount);
		for (int i = 0; i < attributeLocalesCount; i++) {
			attributeLocales.add(kryo.readObject(input, Locale.class));
		}

		final int associatedDataKeyCount = input.readVarInt(true);
		final Set<AssociatedDataKey> associatedDataKeys = new LinkedHashSet<>(associatedDataKeyCount);
		for (int i = 0; i < associatedDataKeyCount; i++) {
			associatedDataKeys.add(this.keyCompressor.getKeyForId(input.readVarInt(true)));
		}
		return new EntityBodyStoragePart(
			version, entityPrimaryKey, Scope.LIVE, hierarchicalPlacement,
			locales, attributeLocales, associatedDataKeys,
			Math.toIntExact(input.total() - totalBefore)
		);
	}

}
