/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
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
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.ReferenceContent;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceContent} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class ReferenceContentSerializer extends Serializer<ReferenceContent> {

	@Override
	public void write(Kryo kryo, Output output, ReferenceContent object) {
		final String[] referencedEntityType = object.getReferenceNames();
		output.writeVarInt(referencedEntityType.length, true);
		for (String refEntityType : referencedEntityType) {
			output.writeString(refEntityType);
		}

		kryo.writeClassAndObject(output, object.getEntityRequirement());

		kryo.writeClassAndObject(output, object.getFilterBy());
	}

	@Override
	public ReferenceContent read(Kryo kryo, Input input, Class<? extends ReferenceContent> type) {
		final int referencedEntityTypeCount = input.readVarInt(true);
		final String[] referencedEntityTypes = new String[referencedEntityTypeCount];
		for (int i = 0; i < referencedEntityTypeCount; i++) {
			referencedEntityTypes[i] = input.readString();
		}

		final EntityFetch entityFetch = (EntityFetch) kryo.readClassAndObject(input);

		final FilterBy filter = (FilterBy) kryo.readClassAndObject(input);

		if (filter == null) {
			return new ReferenceContent(referencedEntityTypes, entityFetch);
		}
		return new ReferenceContent(referencedEntityTypes[0], filter, entityFetch);
	}

}
