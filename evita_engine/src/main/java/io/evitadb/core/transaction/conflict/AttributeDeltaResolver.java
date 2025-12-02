/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025
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

package io.evitadb.core.transaction.conflict;

import io.evitadb.api.requestResponse.data.AttributesContract;
import io.evitadb.api.requestResponse.mutation.conflict.AttributeDeltaConflictKey;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.entity.model.entity.AttributesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Resolver for attribute delta conflicts in concurrent transactions.
 *
 * This resolver handles conflicts when multiple transactions apply delta mutations to the same numeric attribute.
 * It maintains an accumulated value by summing all delta changes, starting from the current attribute value
 * stored in the catalog. This allows commutative operations where the order of delta applications doesn't
 * affect the final result.
 *
 * The resolver retrieves the initial attribute value from the entity's storage part and then accumulates
 * all subsequent delta values during conflict resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class AttributeDeltaResolver extends AbstractAttributeDeltaResolver<AttributeDeltaConflictKey> {

    /**
     * Constructs a new AttributeDeltaResolver by initializing the accumulated value from the catalog.
     *
     * The resolver retrieves the current attribute value from the entity's attributes storage part.
     * If the entity or attribute doesn't exist yet, the accumulated value starts with the provided delta.
     * Otherwise, the initial delta is applied to the existing attribute value using the aggregation
     * function specified in the conflict key.
     *
     * @param catalog the catalog containing the entity collection
     * @param attributeDeltaConflictKey the conflict key identifying the attribute and providing the initial delta value
     */
    public AttributeDeltaResolver(
        @Nonnull Catalog catalog,
        @Nonnull AttributeDeltaConflictKey attributeDeltaConflictKey
    ) {
        super(catalog, attributeDeltaConflictKey);
    }

    @Nullable
    @Override
    protected Number getInitialAttributeValue() {
        final String entityType = this.conflictKey.entityType();
        final Integer entityPrimaryKey = this.conflictKey.entityPrimaryKey();
        final AttributesContract.AttributeKey attributeKey = this.conflictKey.attributeKey();
        if (entityPrimaryKey != null) {
            final EntityCollection entityCollection = this.catalog.getCollectionForEntityOrThrowException(entityType);
            // this might be somewhat expensive, but we don't have any other option how to obtain the existing value
            // of the attribute, if this when it is found that this method is slow, we'd need to introduce some caching layer
            final AttributesStoragePart attributesStoragePart = entityCollection.fetch(
                this.catalog.getVersion(),
                new AttributesStoragePart.EntityAttributesSetKey(
                    entityPrimaryKey,
                    attributeKey.locale()
                ),
                AttributesStoragePart.class,
                AttributesStoragePart::computeUniquePartId
            );
            if (attributesStoragePart != null) {
                for (AttributesContract.AttributeValue attribute : attributesStoragePart.getAttributes()) {
                    if (attribute.key().equals(attributeKey)) {
                        final Number attributeValue = (Number) attribute.value();
                        if (attributeValue != null) {
                            return attributeValue;
                        }
                        break;
                    }
                }
            }
        }
        return null;
    }
}
