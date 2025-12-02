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
import io.evitadb.api.requestResponse.data.ReferenceContract;
import io.evitadb.api.requestResponse.mutation.conflict.ReferenceAttributeDeltaConflictKey;
import io.evitadb.core.Catalog;
import io.evitadb.core.EntityCollection;
import io.evitadb.store.entity.model.entity.ReferencesStoragePart;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Optional;

/**
 * Resolver for reference attribute delta conflicts in concurrent transactions.
 *
 * This resolver handles conflicts when multiple transactions apply delta mutations to the same numeric attribute of
 * a particular reference.
 *
 * It maintains an accumulated value by summing all delta changes, starting from the current attribute value
 * stored in the catalog. This allows commutative operations where the order of delta applications doesn't
 * affect the final result.
 *
 * The resolver retrieves the initial attribute value from the entity's storage part and then accumulates
 * all subsequent delta values during conflict resolution.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2025
 */
public class ReferenceAttributeDeltaResolver extends AbstractAttributeDeltaResolver<ReferenceAttributeDeltaConflictKey> {

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
    public ReferenceAttributeDeltaResolver(
        @Nonnull Catalog catalog,
        @Nonnull ReferenceAttributeDeltaConflictKey attributeDeltaConflictKey
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
            final ReferencesStoragePart referencesStoragePart = entityCollection.fetch(
                this.catalog.getVersion(),
                entityPrimaryKey,
                ReferencesStoragePart.class
            );
            if (referencesStoragePart != null) {
                final Optional<ReferenceContract> reference = referencesStoragePart.findReference(this.conflictKey.referenceKey());
                if (reference.isPresent()) {
                    final Collection<AttributesContract.AttributeValue> attributeValues = reference.get().getAttributeValues();
                    for (AttributesContract.AttributeValue attribute : attributeValues) {
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
        }
        return null;
    }
}
