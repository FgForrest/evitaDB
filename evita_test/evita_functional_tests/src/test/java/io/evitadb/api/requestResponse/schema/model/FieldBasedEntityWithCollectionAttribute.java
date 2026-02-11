/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2026
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

package io.evitadb.api.requestResponse.schema.model;

import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import lombok.Data;

import javax.annotation.Nonnull;
import java.util.List;

/**
 * Example field-based entity with a Collection (List) type attribute.
 * Tests that `extractFieldType` correctly resolves the generic element type
 * from `List&lt;String&gt;` using `field.getGenericType()` instead of `field.getType()`.
 *
 * @author evitaDB contributors
 */
@Entity
@Data
public class FieldBasedEntityWithCollectionAttribute {

	@PrimaryKey
	private int id;

	@Attribute
	@Nonnull
	private List<String> tags;

}
