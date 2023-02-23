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

package io.evitadb.externalApi.grpc.builders.query.extraResults;

import io.evitadb.api.requestResponse.data.EntityClassifier;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.api.requestResponse.data.structure.InitialEntityBuilder;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents;
import io.evitadb.api.requestResponse.extraResult.HierarchyParents.ParentsByReference;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults;
import io.evitadb.externalApi.grpc.generated.GrpcExtraResults.Builder;
import io.evitadb.externalApi.grpc.generated.GrpcHierarchyParentsByReference;
import io.evitadb.externalApi.grpc.testUtils.GrpcAssertions;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.Test;

import java.util.Map;

/**
 * This test verifies functionalities of methods in {@link GrpcHierarchyParentsBuilder} class.
 *
 * @author Tomáš Pozler, 2022
 */
class GrpcHierarchyParentsBuilderTest {

	@Test
	void buildIntegerParents() {
		final String[] types = {"ref1", "ref2", "ref3"};
		final HierarchyParents integerHierarchyParents = new HierarchyParents(
			null,
			Map.of(
				types[0], new ParentsByReference(types[0], Map.of(1, Map.of(1, new EntityClassifier[]{
					new EntityReference(Entities.CATEGORY, 1),
					new EntityReference(Entities.CATEGORY, 2),
					new EntityReference(Entities.CATEGORY, 3)
				}))),
				types[1], new ParentsByReference(types[1], Map.of(2, Map.of(2, new EntityClassifier[]{
					new EntityReference(Entities.CATEGORY, 4),
					new EntityReference(Entities.CATEGORY, 5),
					new EntityReference(Entities.CATEGORY, 6)
				}))),
				types[2], new ParentsByReference(types[2], Map.of(3, Map.of(3, new EntityClassifier[]{
					new EntityReference(Entities.CATEGORY, 7),
					new EntityReference(Entities.CATEGORY, 8),
					new EntityReference(Entities.CATEGORY, 9)
				})))
			)
		);

		final Builder extraResults = GrpcExtraResults.newBuilder();
		GrpcHierarchyParentsBuilder.buildParents(
			extraResults,
			integerHierarchyParents
		);
		final Map<String, GrpcHierarchyParentsByReference> integerParentByType = extraResults.getHierarchyParentsMap();

		for (String type : types) {
			GrpcAssertions.assertParents(integerHierarchyParents.getParents().get(type), integerParentByType.get(type));
		}
	}

	@Test
	void buildSealedEntityParents() {
		final String[] types = {"ref1", "ref2", "ref3"};
		final HierarchyParents entityHierarchyParents = new HierarchyParents(
			null,
			Map.of(
				types[0], new ParentsByReference(types[0], Map.of(1, Map.of(1, new SealedEntity[]{new InitialEntityBuilder(Entities.CATEGORY, 1).toInstance(), new InitialEntityBuilder(Entities.CATEGORY, 2).toInstance()}))),
				types[1], new ParentsByReference(types[1], Map.of(2, Map.of(2, new SealedEntity[]{new InitialEntityBuilder(Entities.CATEGORY, 3).toInstance()}))),
				types[2], new ParentsByReference(types[2], Map.of(3, Map.of(3, new SealedEntity[]{new InitialEntityBuilder(Entities.CATEGORY, 4).toInstance(), new InitialEntityBuilder(Entities.CATEGORY, 5).toInstance()})))
			)
		);

		final Builder extraResults = GrpcExtraResults.newBuilder();
		GrpcHierarchyParentsBuilder.buildParents(
			extraResults,
			entityHierarchyParents
		);

		final Map<String, GrpcHierarchyParentsByReference> entityParentByType = extraResults.getHierarchyParentsMap();
		for (String type : types) {
			GrpcAssertions.assertParents(entityHierarchyParents.getParents().get(type), entityParentByType.get(type));
		}
	}
}