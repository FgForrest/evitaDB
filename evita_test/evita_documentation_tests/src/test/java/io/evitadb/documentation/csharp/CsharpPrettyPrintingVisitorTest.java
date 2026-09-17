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

package io.evitadb.documentation.csharp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.Query.query;
import static io.evitadb.api.query.QueryConstraints.*;
import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.QUERY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies that the C# code samples generated for the user documentation carry the whole query header. The visitor
 * traverses {@link io.evitadb.api.query.Query#getHead()}; traversing the extracted
 * {@link io.evitadb.api.query.head.Collection} instead would silently drop every `label` from the published sample -
 * exactly the defect the Java printing path had.
 *
 * See issue #1507.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("CsharpPrettyPrintingVisitor - query header rendering")
@Tag(CONTRACT)
@Tag(QUERY)
class CsharpPrettyPrintingVisitorTest {

	@Test
	@DisplayName("Should render the whole head container with its labels")
	void shouldRenderHeadContainerWithLabels() {
		assertEquals(
			"""
			Query(
				Head(
					Collection("Product"),
					Label("query-name", "my-query"),
					Label("url", "/test-url")
				),
				FilterBy(
					EntityPrimaryKeyInSet(1, 2, 3)
				)
			)""",
			CsharpPrettyPrintingVisitor.toString(
				query(
					head(
						collection("Product"),
						label("query-name", "my-query"),
						label("url", "/test-url")
					),
					filterBy(entityPrimaryKeyInSet(1, 2, 3))
				),
				"\t",
				""
			)
		);
	}

	@Test
	@DisplayName("Should keep a bare collection head unwrapped")
	void shouldKeepBareCollectionHeadUnwrapped() {
		assertEquals(
			"""
			Query(
				Collection("Product"),
				FilterBy(
					EntityPrimaryKeyInSet(1)
				)
			)""",
			CsharpPrettyPrintingVisitor.toString(
				query(
					collection("Product"),
					filterBy(entityPrimaryKeyInSet(1))
				),
				"\t",
				""
			)
		);
	}

	@Test
	@DisplayName("Should render a label-only head that carries no collection")
	void shouldRenderLabelOnlyHead() {
		assertEquals(
			"""
			Query(
				Label("query-name", "my-query"),
				FilterBy(
					EntityPrimaryKeyInSet(1)
				)
			)""",
			CsharpPrettyPrintingVisitor.toString(
				query(
					label("query-name", "my-query"),
					filterBy(entityPrimaryKeyInSet(1))
				),
				"\t",
				""
			)
		);
	}

	@Test
	@DisplayName("Should render a query that carries no head at all")
	void shouldRenderQueryWithoutHead() {
		assertEquals(
			"""
			Query(
				FilterBy(
					EntityPrimaryKeyInSet(1)
				)
			)""",
			CsharpPrettyPrintingVisitor.toString(
				query(
					filterBy(entityPrimaryKeyInSet(1))
				),
				"\t",
				""
			)
		);
	}

}
