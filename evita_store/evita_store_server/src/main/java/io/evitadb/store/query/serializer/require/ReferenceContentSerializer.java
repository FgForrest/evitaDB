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

package io.evitadb.store.query.serializer.require;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import io.evitadb.api.query.Constraint;
import io.evitadb.api.query.RequireConstraint;
import io.evitadb.api.query.filter.FilterBy;
import io.evitadb.api.query.order.OrderBy;
import io.evitadb.api.query.require.AttributeContent;
import io.evitadb.api.query.require.ChunkingRequireConstraint;
import io.evitadb.api.query.require.EntityFetch;
import io.evitadb.api.query.require.EntityGroupFetch;
import io.evitadb.api.query.require.ManagedReferencesBehaviour;
import io.evitadb.api.query.require.ReferenceContent;
import lombok.RequiredArgsConstructor;

/**
 * This {@link Serializer} implementation reads/writes {@link ReferenceContent} from/to binary format.
 *
 * The payload is laid out as the reference names, the nullable instance name (alias), the attribute content, the
 * entity and group fetch, the filter and the order, the chunking constraint and finally the
 * {@link ManagedReferencesBehaviour}. The instance name travels next to the names it aliases and the behaviour stays
 * the trailing field. Both of them are *arguments* of the constraint rather than its children, so both take part in
 * its equality and neither may be dropped.
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

		output.writeString(object.getInstanceName());

		kryo.writeObjectOrNull(output, object.getAttributeContent().orElse(null), AttributeContent.class);
		kryo.writeObjectOrNull(output, object.getEntityRequirement().orElse(null), EntityFetch.class);
		kryo.writeObjectOrNull(output, object.getGroupEntityRequirement().orElse(null), EntityGroupFetch.class);

		kryo.writeObjectOrNull(output, object.getFilterBy().orElse(null), FilterBy.class);
		kryo.writeObjectOrNull(output, object.getOrderBy().orElse(null), OrderBy.class);

		kryo.writeClassAndObject(output, object.getChunking().orElse(null));

		kryo.writeObject(output, object.getManagedReferencesBehaviour());
	}

	@Override
	public ReferenceContent read(Kryo kryo, Input input, Class<? extends ReferenceContent> type) {
		final int referencedEntityTypeCount = input.readVarInt(true);
		final String[] referencedEntityName = new String[referencedEntityTypeCount];
		for (int i = 0; i < referencedEntityTypeCount; i++) {
			referencedEntityName[i] = input.readString();
		}

		final String instanceName = input.readString();

		final AttributeContent attributeContent = kryo.readObjectOrNull(input, AttributeContent.class);
		final EntityFetch entityFetch = kryo.readObjectOrNull(input, EntityFetch.class);
		final EntityGroupFetch groupEntityFetch = kryo.readObjectOrNull(input, EntityGroupFetch.class);

		final FilterBy filter = kryo.readObjectOrNull(input, FilterBy.class);
		final OrderBy orderBy = kryo.readObjectOrNull(input, OrderBy.class);

		final ChunkingRequireConstraint chunk = (ChunkingRequireConstraint) kryo.readClassAndObject(input);

		final ManagedReferencesBehaviour managedReferences = kryo.readObject(input, ManagedReferencesBehaviour.class);

		if (instanceName != null) {
			// the instance name is carried by a single constructor - the one the GraphQL API uses to build aliased
			// reference content - which accepts all children in one array and filters the null ones out itself,
			// so the aliased shape needs no branching on the reference name count
			return new ReferenceContent(
				instanceName, managedReferences, referencedEntityName,
				new RequireConstraint[]{attributeContent, entityFetch, groupEntityFetch, chunk},
				new Constraint<?>[]{filter, orderBy}
			);
		}

		if (referencedEntityTypeCount == 0) {
			return attributeContent == null ?
				new ReferenceContent(managedReferences, entityFetch, groupEntityFetch, chunk) :
				new ReferenceContent(managedReferences, attributeContent, entityFetch, groupEntityFetch, chunk);
		} else if (referencedEntityTypeCount == 1) {
			return attributeContent == null ?
				new ReferenceContent(
					managedReferences, referencedEntityName[0], filter, orderBy,
					entityFetch, groupEntityFetch, chunk
				) :
				new ReferenceContent(
					managedReferences, referencedEntityName[0], filter, orderBy,
					attributeContent, entityFetch, groupEntityFetch, chunk
				);
		} else {
			return new ReferenceContent(managedReferences, referencedEntityName, entityFetch, groupEntityFetch, chunk);
		}
	}

}
