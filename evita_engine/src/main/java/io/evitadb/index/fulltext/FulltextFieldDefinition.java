/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2026
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


package io.evitadb.index.fulltext;

import io.evitadb.api.requestResponse.schema.AssociatedDataSchemaContract;
import io.evitadb.api.requestResponse.schema.AttributeSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;
import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;

/**
 * The current schema analysis of a searchable field, shared by index maintenance and query planning.
 *
 * @param key the field's identity
 * @param analyzer the schema analyzer name
 * @param lengthPivot the schema length pivot
 */
public record FulltextFieldDefinition(
	@Nonnull FulltextFieldKey key, @Nonnull String analyzer, double lengthPivot
) {
	/**
	 * Reads the current analysis settings of any kind of searchable field.
	 *
	 * @param schema the entity schema declaring the field
	 * @param key the field to resolve
	 * @return the field's schema settings
	 */
	@Nonnull
	public static FulltextFieldDefinition resolve(
		@Nonnull EntitySchemaContract schema, @Nonnull FulltextFieldKey key
	) {
		return switch (key.kind()) {
			case ASSOCIATED_DATA -> {
				final AssociatedDataSchemaContract data = schema.getAssociatedDataOrThrowException(key.name());
				yield new FulltextFieldDefinition(key, data.getAnalyzer(), data.getLengthPivot());
			}
			case ATTRIBUTE, REFERENCE_ATTRIBUTE -> {
				final AttributeSchemaContract attribute = key.kind() == FulltextFieldKey.FieldKind.ATTRIBUTE ?
					schema.getAttribute(key.name()).orElse(null) :
					schema.getReferenceOrThrowException(key.referenceName()).getAttribute(key.name()).orElse(null);
				Assert.isPremiseValid(attribute != null, "Fulltext field `" + key + "` has no attribute schema.");
				yield new FulltextFieldDefinition(key, attribute.getAnalyzer(), attribute.getLengthPivot());
			}
		};
	}
}
