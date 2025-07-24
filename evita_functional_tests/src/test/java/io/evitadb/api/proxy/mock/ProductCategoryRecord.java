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

package io.evitadb.api.proxy.mock;

import io.evitadb.api.AbstractHundredProductsFunctionalTest;
import io.evitadb.api.requestResponse.data.annotation.AttributeRef;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.data.structure.EntityReference;
import io.evitadb.test.generator.DataGenerator;

import java.io.Serial;
import java.io.Serializable;

/**
 * Variant of a product category mapped as Java record.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public record ProductCategoryRecord(
	@ReferencedEntity int primaryKey,
	@AttributeRef(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY) Long orderInCategory,
	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_CATEGORY_LABEL) String label,
	@ReferencedEntity CategoryRecord category,
	@ReferencedEntity EntityReference categoryReference,
	@ReferencedEntity Integer categoryReferencePrimaryKey
) implements Serializable {
	@Serial private static final long serialVersionUID = 4946751255313861381L;

	// You can add any additional methods or overrides as needed
}
