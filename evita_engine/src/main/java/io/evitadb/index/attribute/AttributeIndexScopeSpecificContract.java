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
 * AttributeIndexScopeSpecificContract declares the scope-parameterized unique-index lookup. It is deliberately kept
 * OUT of the delegated {@link AttributeIndexContract} read surface and implemented only by {@link AttributeIndex}, so
 * that {@link @lombok.experimental.Delegate} never auto-forwards it onto {@link io.evitadb.index.EntityIndex}. An
 * entity index lives in exactly one scope, so it exposes only a scope-locked variant that resolves the scope from its
 * own index key — letting a caller pass an arbitrary scope here would be a correctness footgun.
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
