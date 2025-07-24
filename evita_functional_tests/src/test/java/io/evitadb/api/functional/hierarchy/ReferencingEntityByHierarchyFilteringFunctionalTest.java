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

package io.evitadb.api.functional.hierarchy;

import io.evitadb.api.requestResponse.schema.ReferenceSchemaEditor.ReferenceSchemaBuilder;
import io.evitadb.test.annotation.IsolateDataSetBySuffix;
import io.evitadb.test.extension.EvitaParameterResolver;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.extension.ExtendWith;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestConstants.FUNCTIONAL_TEST;

/**
 * This test verifies whether entities that reference other - hierarchical entity can be filtered by hierarchy constraints.
 * This test verifies correct behavior when the referenced entity is indexed for filtering only.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@DisplayName("Evita referenced entity filtering by hierarchy functionality")
@IsolateDataSetBySuffix("filtering")
@Tag(FUNCTIONAL_TEST)
@ExtendWith(EvitaParameterResolver.class)
@Slf4j
public class ReferencingEntityByHierarchyFilteringFunctionalTest extends AbstractReferencingEntityByHierarchyFunctionalTest {

	@Nonnull
	@Override
	protected ReferenceSchemaBuilder makeReferenceIndexed(ReferenceSchemaBuilder whichIs) {
		return whichIs.indexedForFiltering();
	}
}
