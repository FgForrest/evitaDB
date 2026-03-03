/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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

package io.evitadb.api.query.expression.visitor;

import io.evitadb.api.query.expression.ExpressionFactory;
import io.evitadb.dataType.expression.ExpressionNode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link AccessedDataFinder}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2026
 */
public class AccessedDataFinderTest {

	@ParameterizedTest
	@MethodSource("expressions")
	void shouldResolveAssessedDataPaths(List<List<PathItem>> expectedPaths, String expression) {
		final ExpressionNode root = ExpressionFactory.parse(expression);
		assertEquals(expectedPaths, AccessedDataFinder.findAccessedPaths(root));
	}

	@Nonnull
	static Stream<Arguments> expressions() {
		return Stream.of(
			Arguments.of(
				List.of(),
				"1 > 2 || random() < 10"
			),
			Arguments.of(
				List.of(),
				"$pageNumber < 2"
			),
			Arguments.of(
				List.of(List.of(new VariablePathItem("entity"), new IdentifierPathItem("attributes"))),
				"$entity.attributes"
			),
			Arguments.of(
				List.of(List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new IdentifierPathItem("attributes"))),
				"$entity.references.attributes"
			),
			Arguments.of(
				List.of(List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("brand"), new IdentifierPathItem("attributes"), new ElementPathItem("order"))),
				"$entity.references['brand'].attributes['order']"
			),
			Arguments.of(
				List.of(List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("categories"), new IdentifierPathItem("attributes"), new ElementPathItem("tag"))),
				"$entity.references['categories'].*[$.attributes['tag']]"
			),
			Arguments.of(
				List.of(
					List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("brand"), new IdentifierPathItem("attributes"), new ElementPathItem("tag")),
					List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("brand"), new IdentifierPathItem("attributes"), new ElementPathItem("fallbackTag")),
					List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("brand"), new IdentifierPathItem("referencedEntity"))
				),
				"true || $entity.references['brand'].*[$.attributes['tag'] ?? $.attributes['fallbackTag'] ?? $.referencedEntity]"
			),
			Arguments.of(
				List.of(
					List.of(new VariablePathItem("entity"), new IdentifierPathItem("references"), new ElementPathItem("brand"), new IdentifierPathItem("attributes"), new ElementPathItem("tag")),
					List.of(new VariablePathItem("entity"), new IdentifierPathItem("attributes"), new ElementPathItem("fallbackTag"))
				),
				"$entity.references['brand']?.*[$.attributes['tag']] ?*? $entity.attributes['fallbackTag'] ?? 'none']"
			)
		);
	}
}
