/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.index.attribute;


import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.dataType.Scope;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;

/**
 * AttributeIndexContract describes the API of {@link AttributeIndex} that maintains data structures for fast accessing
 * filtered, unique and sorted entity attribute data. Interface describes both read and write access to the index.
 *
 * Purpose of this contract interface is to ease using {@link @lombok.experimental.Delegate} annotation
 * in {@link io.evitadb.index.EntityIndex} and minimize the amount of the code in this complex class by automatically
 * delegating all {@link AttributeIndexContract} methods to the {@link AttributeIndex} implementation that is part
 * of this index.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
public interface AttributeIndexScopeSpecificContract {

	/**
	 * Returns index that maintains unique attributes to record ids information.
	 *
	 * @param referenceSchema The reference schema contract that is envelope for compound attribute schema contract.
	 *                        Can be null when attribute is defined on entity level.
	 * @param attributeSchema schema to be used for checking uniqueness of the attribute
	 * @param scope scope to check uniqueness in
	 * @param locale might not be passed for language agnostic attributes
	 * @return NULL value when there is no unique index associated with this `attributeSchema`
	 */
	@Nullable
	UniqueIndex getUniqueIndex(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull AttributeSchemaContract attributeSchema,
		@Nonnull Scope scope,
		@Nullable Locale locale
	);

}
