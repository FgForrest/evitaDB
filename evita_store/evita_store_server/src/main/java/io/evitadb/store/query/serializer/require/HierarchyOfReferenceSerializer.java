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
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.EmptyHierarchicalEntityBehaviour;
import io.evitadb.api.query.require.HierarchyOfReference;
import io.evitadb.api.query.require.HierarchyRequireConstraint;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link HierarchyOfReference} from/to binary format.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
@RequiredArgsConstructor
public class HierarchyOfReferenceSerializer extends Serializer<HierarchyOfReference> {

	@Override
	public void write(Kryo kryo, Output output, HierarchyOfReference object) {
		final String[] entityTypes = object.getReferenceNames();
		output.writeVarInt(entityTypes.length, true);
		for (String entityType : entityTypes) {
			output.writeString(entityType);
		}
		kryo.writeObject(output, object.getEmptyHierarchicalEntityBehaviour());
		final HierarchyRequireConstraint[] requirements = object.getRequirements();
		output.writeVarInt(requirements.length, true);
		for (HierarchyRequireConstraint requirement : requirements) {
			kryo.writeClassAndObject(output, requirement);
		}
		kryo.writeObjectOrNull(output, object.getOrderBy().orElse(null), OrderBy.class);
	}

	@Override
	public HierarchyOfReference read(Kryo kryo, Input input, Class<? extends HierarchyOfReference> type) {
		final int entityTypeCount = input.readVarInt(true);
		final String[] entityTypes = new String[entityTypeCount];
		for (int i = 0; i < entityTypeCount; i++) {
			entityTypes[i] = input.readString();
		}

		final EmptyHierarchicalEntityBehaviour emptyHierarchicalEntityBehaviour =
			kryo.readObject(input, EmptyHierarchicalEntityBehaviour.class);

		final int requirementCount = input.readVarInt(true);
		final HierarchyRequireConstraint[] requirements = new HierarchyRequireConstraint[requirementCount];
		for (int i = 0; i < requirementCount; i++) {
			requirements[i] = (HierarchyRequireConstraint) kryo.readClassAndObject(input);
		}

		final OrderBy orderBy = kryo.readObjectOrNull(input, OrderBy.class);
		return new HierarchyOfReference(entityTypes, emptyHierarchicalEntityBehaviour, orderBy, requirements);
	}

}
