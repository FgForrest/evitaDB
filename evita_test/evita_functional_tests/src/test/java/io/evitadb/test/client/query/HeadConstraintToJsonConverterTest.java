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

package io.evitadb.test.client.query;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.GenericDataLocator;
import io.evitadb.externalApi.api.catalog.dataApi.constraint.ManagedEntityTypePointer;
import io.evitadb.test.Entities;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.collection;
import static io.evitadb.api.query.QueryConstraints.label;
import static io.evitadb.test.TestTags.ENGINE;
import static io.evitadb.test.TestTags.MANAGEMENT;
import static io.evitadb.test.TestTags.REQUIRE;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for `HeadConstraintToJsonConverter` verifying that query header constraints (`collection`,
 * `label`) are converted into the JSON wire representation shared with the REST and GraphQL APIs.
 *
 * The expected JSON shapes mirror the value structures the converter derives from each constraint's
 * `@Creator`: `collection` carries a single value parameter and renders as a primitive text node,
 * while `label` carries two value parameters and renders as a wrapper object keyed by the creator
 * parameter names.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
@Tag(ENGINE)
@Tag(MANAGEMENT)
@Tag(REQUIRE)
@DisplayName("HeadConstraintToJsonConverter functionality")
class HeadConstraintToJsonConverterTest extends AbstractConstraintToJsonConverterTest {

	private HeadConstraintToJsonConverter converter;

	@BeforeEach
	void init() {
		super.init();
		this.converter = new HeadConstraintToJsonConverter(this.catalogSchema);
	}

	@Test
	@DisplayName("Should convert collection head constraint into a primitive text node")
	void shouldConvertCollectionHeadConstraint() {
		assertEquals(
			new JsonConstraint(
				"collection",
				jsonNodeFactory.textNode(Entities.PRODUCT)
			),
			this.converter.convert(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				collection(Entities.PRODUCT)
			).orElseThrow()
		);
	}

	@Test
	@DisplayName("Should convert label head constraint into a name/value wrapper object")
	void shouldConvertLabelHeadConstraint() {
		final ObjectNode wrapperObject = jsonNodeFactory.objectNode();
		wrapperObject.putIfAbsent("name", jsonNodeFactory.textNode("query-name"));
		wrapperObject.putIfAbsent("value", jsonNodeFactory.textNode("List all products"));

		assertEquals(
			new JsonConstraint("label", wrapperObject),
			this.converter.convert(
				new GenericDataLocator(new ManagedEntityTypePointer(Entities.PRODUCT)),
				label("query-name", "List all products")
			).orElseThrow()
		);
	}

}
