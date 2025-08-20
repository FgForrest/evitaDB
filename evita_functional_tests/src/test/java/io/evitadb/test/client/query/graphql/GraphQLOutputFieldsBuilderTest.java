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

package io.evitadb.test.client.query.graphql;

import io.evitadb.externalApi.api.catalog.dataApi.model.EntityDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.ReferenceDescriptor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link GraphQLOutputFieldsBuilder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
class GraphQLOutputFieldsBuilderTest {

	@Test
	void shouldRenderFieldsCorrectly() {
		final String expectedFields = """
			  primaryKey
			  type
			  store {
			    referencedPrimaryKey
			    referencedEntity {
			      primaryKey
			    }
			  }
			""".stripTrailing();
		assertEquals(
			expectedFields,
			new GraphQLOutputFieldsBuilder(0)
				.addPrimitiveField(EntityDescriptor.PRIMARY_KEY)
				.addPrimitiveField(EntityDescriptor.TYPE)
				.addObjectField("store", b1 -> b1
					.addPrimitiveField(ReferenceDescriptor.REFERENCED_PRIMARY_KEY)
					.addObjectField(ReferenceDescriptor.REFERENCED_ENTITY, b2 -> b2
						.addPrimitiveField(EntityDescriptor.PRIMARY_KEY)))
				.build()
		);
	}
}
