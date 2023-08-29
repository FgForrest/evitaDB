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

package io.evitadb.externalApi.lab.api.resolver.serializer;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.api.requestResponse.data.EntityContract;
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.externalApi.lab.api.model.entity.GenericEntityDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.serializer.EntityJsonSerializer;
import io.evitadb.externalApi.rest.io.RestHandlingContext;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nonnull;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Handles serializing of Evita entity into generic JSON structure
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2022
 */
@Slf4j
public class GenericEntityJsonSerializer extends EntityJsonSerializer {

	public GenericEntityJsonSerializer(@Nonnull RestHandlingContext restHandlingContext) {
		super(restHandlingContext);
	}

	@Override
	protected void serializeReferences(@Nonnull ObjectNode rootNode, @Nonnull EntityContract entity) {
		if (entity.referencesAvailable() && !entity.getReferences().isEmpty()) {
			final ObjectNode referencesObject = objectJsonSerializer.objectNode();

			entity.getReferences()
				.stream()
				.map(ReferenceContract::getReferenceName)
				.collect(Collectors.toCollection(TreeSet::new))
				.forEach(it -> serializeReferencesWithSameName(referencesObject, entity, it));

			rootNode.putIfAbsent(GenericEntityDescriptor.REFERENCES.name(), referencesObject);
		}
	}
}