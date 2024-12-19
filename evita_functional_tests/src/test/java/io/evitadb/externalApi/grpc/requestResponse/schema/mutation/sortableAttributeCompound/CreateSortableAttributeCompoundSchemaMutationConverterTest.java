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

package io.evitadb.externalApi.grpc.requestResponse.schema.mutation.sortableAttributeCompound;

import io.evitadb.api.requestResponse.schema.mutation.sortableAttributeCompound.CreateSortableAttributeCompoundSchemaMutation;
import io.evitadb.dataType.Scope;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.requestResponse.schema.SortableAttributeCompoundSchemaContract.AttributeElement.attributeElement;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateSortableAttributeCompoundSchemaMutationConverterTest {

	private static CreateSortableAttributeCompoundSchemaMutationConverter converter;

	@BeforeAll
	static void setup() {
		converter = CreateSortableAttributeCompoundSchemaMutationConverter.INSTANCE;
	}

	@Test
	void shouldConvertMutation() {
		final CreateSortableAttributeCompoundSchemaMutation mutation1 = new CreateSortableAttributeCompoundSchemaMutation(
			"code",
			"desc",
			"depr",
			new Scope[] { Scope.LIVE },
			attributeElement("code"),
			attributeElement("name")
		);
		assertEquals(mutation1, converter.convert(converter.convert(mutation1)));

		final CreateSortableAttributeCompoundSchemaMutation mutation2 = new CreateSortableAttributeCompoundSchemaMutation(
			"code",
			null,
			null,
			new Scope[] { Scope.LIVE },
			attributeElement("code"),
			attributeElement("name")
		);
		assertEquals(mutation2, converter.convert(converter.convert(mutation2)));
	}
}
