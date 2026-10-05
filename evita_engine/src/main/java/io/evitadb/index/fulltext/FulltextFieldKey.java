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

import io.evitadb.utils.Assert;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Identity of one searchable field in a {@link FulltextIndex}: what kind of entity data the field's text comes from,
 * and which piece of it.
 *
 * The kind is part of the identity because the namespaces it separates are independent in the schema: an attribute
 * and an associated data item may share a name, and so may a reference attribute and an entity attribute - or two
 * reference attributes of different references. A field keyed by its name alone would make each such pair one field.
 *
 * A global attribute is an entity attribute here: it shares the entity attributes' namespace in the entity schema,
 * and its value is the entity's own.
 *
 * @param kind          the kind of entity data the field indexes
 * @param referenceName name of the reference whose attribute the field indexes; set for
 *                      {@link FieldKind#REFERENCE_ATTRIBUTE} and only for it
 * @param name          name of the attribute or associated data item
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public record FulltextFieldKey(
	@Nonnull FieldKind kind,
	@Nullable String referenceName,
	@Nonnull String name
) {

	/**
	 * The kinds of entity data a searchable field indexes.
	 */
	public enum FieldKind {

		/**
		 * An attribute of the entity, global attributes included.
		 */
		ATTRIBUTE,
		/**
		 * An associated data item of the entity.
		 */
		ASSOCIATED_DATA,
		/**
		 * An attribute of the entity's references of one name. The entity's value of the field is the set of the
		 * attribute's values over all its references of that name.
		 */
		REFERENCE_ATTRIBUTE

	}

	/**
	 * Verifies the reference name is present exactly when the kind needs one.
	 */
	public FulltextFieldKey {
		final boolean needsReference = kind == FieldKind.REFERENCE_ATTRIBUTE;
		Assert.isPremiseValid(
			needsReference == (referenceName != null),
			() -> "A fulltext field of kind " + kind + " must " + (needsReference ? "" : "not ") +
				"name a reference, `" + referenceName + "` was passed!"
		);
	}

	/**
	 * Returns the key of the field indexing an entity attribute.
	 *
	 * @param attributeName name of the attribute
	 * @return the field key
	 */
	@Nonnull
	public static FulltextFieldKey attribute(@Nonnull String attributeName) {
		return new FulltextFieldKey(FieldKind.ATTRIBUTE, null, attributeName);
	}

	/**
	 * Returns the key of the field indexing an associated data item.
	 *
	 * @param associatedDataName name of the associated data item
	 * @return the field key
	 */
	@Nonnull
	public static FulltextFieldKey associatedData(@Nonnull String associatedDataName) {
		return new FulltextFieldKey(FieldKind.ASSOCIATED_DATA, null, associatedDataName);
	}

	/**
	 * Returns the key of the field indexing an attribute of the entity's references of one name.
	 *
	 * @param referenceName name of the reference
	 * @param attributeName name of the reference attribute
	 * @return the field key
	 */
	@Nonnull
	public static FulltextFieldKey referenceAttribute(@Nonnull String referenceName, @Nonnull String attributeName) {
		return new FulltextFieldKey(FieldKind.REFERENCE_ATTRIBUTE, referenceName, attributeName);
	}

	@Nonnull
	@Override
	public String toString() {
		return this.referenceName == null
			? this.kind + " `" + this.name + "`"
			: this.kind + " `" + this.referenceName + "." + this.name + "`";
	}

}
