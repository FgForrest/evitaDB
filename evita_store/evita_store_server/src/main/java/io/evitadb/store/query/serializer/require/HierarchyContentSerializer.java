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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.HierarchyContent;
import io.evitadb.api.query.require.HierarchyParentsBehaviour;
import io.evitadb.api.query.require.HierarchyStopAt;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyContent} from/to binary format.
 *
 * The payload is laid out as the nullable {@link EntityFetch}, the nullable {@link HierarchyStopAt} and finally the
 * {@link HierarchyParentsBehaviour}. The behaviour is the trailing field. It is an *argument* of the constraint
 * rather than one of its children, so it takes part in the constraint's equality and may not be dropped - a payload
 * that omits it replays as the default and silently rewrites what the recorded query asked for.
 *
 * Adding that field is a format change without a compatibility reader, exactly as it is for the sibling
 * {@link ReferenceContentSerializer} - see the wording there for who reads such a payload and why no compatible
 * middle ground exists for a query-constraint serializer.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyContentSerializer extends Serializer<HierarchyContent> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyContent hierarchyContent) {
		kryo.writeObjectOrNull(output, hierarchyContent.getEntityFetch().orElse(null), EntityFetch.class);
		kryo.writeObjectOrNull(output, hierarchyContent.getStopAt().orElse(null), HierarchyStopAt.class);

		kryo.writeObject(output, hierarchyContent.getParentsBehaviour());
	}

	@Override
	public HierarchyContent read(Kryo kryo, Input input, Class<? extends HierarchyContent> type) {
		final EntityFetch entityFetch = kryo.readObjectOrNull(input, EntityFetch.class);
		final HierarchyStopAt stopAt = kryo.readObjectOrNull(input, HierarchyStopAt.class);

		final HierarchyParentsBehaviour parentsBehaviour = kryo.readObject(input, HierarchyParentsBehaviour.class);

		return new HierarchyContent(parentsBehaviour, stopAt, entityFetch);
	}

}
