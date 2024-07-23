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
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyStopAt;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyContent} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyContentSerializer extends Serializer<HierarchyContent> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyContent hierarchyContent) {
		kryo.writeObjectOrNull(output, hierarchyContent.getEntityFetch().orElse(null), EntityFetch.class);
		kryo.writeObjectOrNull(output, hierarchyContent.getStopAt().orElse(null), HierarchyStopAt.class);
	}

	@Override
	public HierarchyContent read(Kryo kryo, Input input, Class<? extends HierarchyContent> type) {
		final EntityFetch entityFetch = kryo.readObjectOrNull(input, EntityFetch.class);
		final HierarchyStopAt stopAt = kryo.readObjectOrNull(input, HierarchyStopAt.class);
		return new HierarchyContent(stopAt, entityFetch);
	}

}
