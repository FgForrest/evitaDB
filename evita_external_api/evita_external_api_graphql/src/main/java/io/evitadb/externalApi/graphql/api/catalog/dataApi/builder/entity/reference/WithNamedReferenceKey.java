/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2025-2026
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

package io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.entity.reference;

import io.evitadb.api.requestResponse.schema.Cardinality;
import io.evitadb.api.requestResponse.schema.ReferenceSchemaContract;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.EntityReferenceDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.ReferenceDefinitionDescriptor;
import io.evitadb.externalApi.api.catalog.dataApi.model.entity.reference.WithNamedReferenceDescriptor;
import io.evitadb.externalApi.graphql.api.catalog.dataApi.builder.EntityObjectBuilder.EntityObjectVariant;
import lombok.EqualsAndHashCode;
import lombok.RequiredArgsConstructor;
import net.openhft.hashing.LongHashFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Defines a unique (within a catalog) key for a "WithReference" interface ({@link WithNamedReferenceDescriptor}).
 * That is an interface containing fields to access a {@link ReferenceDefinitionDescriptor named reference},
 * but it is not directly associated with a specific entity type like {@link EntityReferenceDescriptor}.
 * Instead, entity objects implement this interface if they match the reference definition.
 * This allows us to reuse this interface for multiple entity types with the same reference definition (based on data equality)
 * even though they are not explicitly associated with each other.
 * <p>
 * The key is compared based on generic reference definition data,
 * but the generated hash (used for the type name used by clients) is generated from the template entity collection where
 * the reference definition was discovered on. This is to ensure some kind of stability during reference definition schema changes.
 * <p>
 * For example, if the attributes change, the generated hash will remain the same (and thus the client type names will
 * not be affected) as long as all of the reference definitions are changed in the same way.
 * <p>
 * Template entity type means the first entity type that this reference definition was discovered on. The same goes for the
 * template reference name.
 *
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2025
 */
@RequiredArgsConstructor
@EqualsAndHashCode(of = {
	"referenceName",
	"referencedEntityType",
	"referencedGroupType",
	"cardinality",
	"description",
	"deprecationNotice",
	"attributes",
	"fieldsVariant"
})
public class WithNamedReferenceKey {

	@Nonnull private final String referenceName;
	@Nonnull private final String referencedEntityType;
	@Nullable private final String referencedGroupType;
	@Nonnull private final Cardinality cardinality;
	@Nullable private final String description;
	@Nullable private final String deprecationNotice;
	@Nullable private final ReferenceAttributesKey attributes;
	@Nonnull private final EntityObjectVariant fieldsVariant;

	@Nonnull private final String templateEntityType;
	@Nonnull private final String templateReferenceName;
	@Nonnull private final EntityObjectVariant templateEntityVersion;

	public WithNamedReferenceKey(
		@Nonnull ReferenceSchemaContract referenceSchema,
		@Nonnull EntityObjectVariant fieldsVariant,
		@Nonnull String templateEntityType,
		@Nonnull String templateReferenceName,
		@Nonnull EntityObjectVariant templateEntityVersion
	) {
		this.referenceName = referenceSchema.getName();
		this.referencedEntityType = referenceSchema.getReferencedEntityType();
		this.referencedGroupType = referenceSchema.getReferencedGroupType();
		this.cardinality = referenceSchema.getCardinality();
		this.description = referenceSchema.getDescription();
		this.deprecationNotice = referenceSchema.getDeprecationNotice();
		this.attributes = new ReferenceAttributesKey(referenceSchema);
		this.fieldsVariant = fieldsVariant;

		this.templateEntityType = templateEntityType;
		this.templateReferenceName = templateReferenceName;
		this.templateEntityVersion = templateEntityVersion;
	}

	public long toHash() {
		final LongHashFunction hashFunction = LongHashFunction.xx3();
		return hashFunction.hashLongs(new long[] {
			hashFunction.hashChars(this.templateEntityType),
			hashFunction.hashChars(this.templateReferenceName),
			hashFunction.hashChars(this.templateEntityVersion.name())
		});
	}
}
