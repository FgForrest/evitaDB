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

package io.evitadb.api.requestResponse.schema.builder;

import io.evitadb.api.requestResponse.schema.CatalogSchemaContract;
import io.evitadb.api.requestResponse.schema.EntitySchemaContract;

import javax.annotation.Nonnull;
import java.util.Objects;

/**
 * Compares two schemas by their content and ignores the version counter, which grows with every applied mutation even
 * when the mutation leaves the schema as it was.
 *
 * The schema builders use the comparison in `toMutation()` to recognise that the mutations they collected bring the
 * schema back to its base state - for example when an annotation value is set and a schema post-processor restores
 * the stored one afterwards. Such mutations change nothing, yet the server would still record them in the
 * transaction and derive conflict keys from them, so the builders do not emit them at all.
 *
 * The schema DTOs cannot be used for this directly: `EntitySchema` and `CatalogSchema` define their identity only by
 * name and version. The comparison therefore goes property by property through the schema contracts and relies on the
 * value equality of the nested sub-schemas (attributes, associated data, references, sortable attribute compounds).
 * A property missing here would make the builders drop a genuine change of that property, so every new schema
 * property must be added - `SchemaContentComparatorTest` fails when a schema DTO gains a field this class does not
 * account for.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
final class SchemaContentComparator {

	private SchemaContentComparator() {
		// utility class
	}

	/**
	 * Returns true when both entity schemas have the same content. The version of the schemas is not compared.
	 *
	 * @param baseSchema    the entity schema the builder started from
	 * @param updatedSchema the entity schema after all collected mutations were applied
	 * @return true when the updated schema does not differ from the base schema in any property
	 */
	static boolean isEntitySchemaContentEqual(
		@Nonnull EntitySchemaContract baseSchema,
		@Nonnull EntitySchemaContract updatedSchema
	) {
		if (baseSchema == updatedSchema) {
			return true;
		}
		return baseSchema.getName().equals(updatedSchema.getName()) &&
			Objects.equals(baseSchema.getNameVariants(), updatedSchema.getNameVariants()) &&
			Objects.equals(baseSchema.getDescription(), updatedSchema.getDescription()) &&
			Objects.equals(baseSchema.getDeprecationNotice(), updatedSchema.getDeprecationNotice()) &&
			Objects.equals(baseSchema.getConflictResolution(), updatedSchema.getConflictResolution()) &&
			baseSchema.isWithGeneratedPrimaryKey() == updatedSchema.isWithGeneratedPrimaryKey() &&
			baseSchema.isWithHierarchy() == updatedSchema.isWithHierarchy() &&
			Objects.equals(baseSchema.getHierarchyIndexedInScopes(), updatedSchema.getHierarchyIndexedInScopes()) &&
			baseSchema.isWithPrice() == updatedSchema.isWithPrice() &&
			Objects.equals(baseSchema.getPriceIndexedInScopes(), updatedSchema.getPriceIndexedInScopes()) &&
			baseSchema.getIndexedPricePlaces() == updatedSchema.getIndexedPricePlaces() &&
			Objects.equals(baseSchema.getLocales(), updatedSchema.getLocales()) &&
			Objects.equals(baseSchema.getCurrencies(), updatedSchema.getCurrencies()) &&
			Objects.equals(baseSchema.getEvolutionMode(), updatedSchema.getEvolutionMode()) &&
			Objects.equals(baseSchema.getAttributes(), updatedSchema.getAttributes()) &&
			Objects.equals(baseSchema.getSortableAttributeCompounds(), updatedSchema.getSortableAttributeCompounds()) &&
			Objects.equals(baseSchema.getAssociatedData(), updatedSchema.getAssociatedData()) &&
			Objects.equals(baseSchema.getReferences(), updatedSchema.getReferences());
	}

	/**
	 * Returns true when both catalog schemas have the same catalog-level content. Entity schemas are not part of the
	 * comparison - the catalog builder judges them separately - and neither is the version of the schemas.
	 *
	 * @param baseSchema    the catalog schema the builder started from
	 * @param updatedSchema the catalog schema after all collected mutations were applied
	 * @return true when the updated schema does not differ from the base schema in any catalog-level property
	 */
	static boolean isCatalogSchemaContentEqual(
		@Nonnull CatalogSchemaContract baseSchema,
		@Nonnull CatalogSchemaContract updatedSchema
	) {
		if (baseSchema == updatedSchema) {
			return true;
		}
		return baseSchema.getName().equals(updatedSchema.getName()) &&
			Objects.equals(baseSchema.getNameVariants(), updatedSchema.getNameVariants()) &&
			Objects.equals(baseSchema.getDescription(), updatedSchema.getDescription()) &&
			Objects.equals(baseSchema.getConflictResolution(), updatedSchema.getConflictResolution()) &&
			Objects.equals(baseSchema.getCatalogEvolutionMode(), updatedSchema.getCatalogEvolutionMode()) &&
			Objects.equals(baseSchema.getAttributes(), updatedSchema.getAttributes());
	}

}
