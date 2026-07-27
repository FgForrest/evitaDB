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

package io.evitadb.core.expression.proxy;

import io.evitadb.api.requestResponse.data.AttributesContract.AttributeValue;
import io.evitadb.api.requestResponse.data.ReferenceContract.GroupEntityReference;
import io.evitadb.api.requestResponse.data.SealedEntity;
import io.evitadb.api.requestResponse.data.mutation.reference.ReferenceKey;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.Serial;
import java.io.Serializable;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable state record holding data needed by ReferenceContract proxy partials during expression evaluation.
 *
 * @param referenceSchema  reference schema - always available
 * @param referenceKey     reference key - always available (identity)
 * @param version          reference version from the Reference entry
 * @param attributes       reference-level attributes (sorted array, supports binary search)
 * @param attributeLocales locales present in reference attributes
 * @param group            group entity reference (nullable)
 * @param referencedEntity nested entity proxy for referenced entity (nullable)
 * @param groupEntity      nested entity proxy for group entity (nullable)
 */
public record ReferenceProxyState(
	@Nonnull ReferenceSchemaContract referenceSchema,
	@Nonnull ReferenceKey referenceKey,
	int version,
	@Nullable AttributeValue[] attributes,
	@Nonnull Set<Locale> attributeLocales,
	@Nullable GroupEntityReference group,
	@Nullable SealedEntity referencedEntity,
	@Nullable SealedEntity groupEntity
) implements Serializable {
	@Serial private static final long serialVersionUID = -765739514140048871L;

}
