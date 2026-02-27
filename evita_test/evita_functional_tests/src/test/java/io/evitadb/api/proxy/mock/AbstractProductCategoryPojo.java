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
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * Variant of a product category mapped as POJO class.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
@Data
@RequiredArgsConstructor
public abstract class AbstractProductCategoryPojo implements Serializable {
	@Serial private static final long serialVersionUID = 3483391875584670861L;
	@ReferencedEntity private final int primaryKey;
	@AttributeRef(DataGenerator.ATTRIBUTE_CATEGORY_PRIORITY) private final Long orderInCategory;
	@AttributeRef(AbstractHundredProductsFunctionalTest.ATTRIBUTE_CATEGORY_LABEL) private final String label;
	@ReferencedEntity private final AbstractCategoryPojo category;
	@ReferencedEntity private final EntityReference categoryReference;
	@ReferencedEntity private final Integer categoryReferencePrimaryKey;

}
