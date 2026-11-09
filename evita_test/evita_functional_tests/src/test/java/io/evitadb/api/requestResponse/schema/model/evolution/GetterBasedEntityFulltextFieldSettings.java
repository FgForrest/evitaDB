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


package io.evitadb.api.requestResponse.schema.model.evolution;

import io.evitadb.api.requestResponse.data.annotation.AssociatedData;
import io.evitadb.api.requestResponse.data.annotation.Attribute;
import io.evitadb.api.requestResponse.data.annotation.Entity;
import io.evitadb.api.requestResponse.data.annotation.PrimaryKey;
import io.evitadb.api.requestResponse.data.annotation.Reference;
import io.evitadb.api.requestResponse.data.annotation.ReferencedEntity;
import io.evitadb.api.requestResponse.schema.FulltextAnalyzerSchemaContract;

import java.io.Serializable;

/**
 * Model for the fulltext field settings declared by annotation: the analyzer and the length pivot on an entity
 * attribute, a global attribute, a reference attribute and an associated data, next to a searchable attribute that
 * leaves both at their defaults.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Entity(name = GetterBasedEntityFulltextFieldSettings.ENTITY_NAME)
public interface GetterBasedEntityFulltextFieldSettings {

	String ENTITY_NAME = "GetterBasedEntityFulltextFieldSettings";

	@PrimaryKey
	int getId();

	@Attribute(
		localized = true, searchable = true, analyzer = FulltextAnalyzerSchemaContract.HTML_TEXT, lengthPivot = 8,
		searchWeight = 2.5f
	)
	String getName();

	@Attribute(localized = true, searchable = true)
	String getPlainName();

	@Attribute(global = true, localized = true, searchable = true, lengthPivot = 12, searchWeight = 3.5f)
	String getFieldSettingsGlobalTitle();

	@AssociatedData(
		localized = true, searchable = true, analyzer = FulltextAnalyzerSchemaContract.HTML_TEXT, lengthPivot = 400,
		searchWeight = 0.0f
	)
	String getBody();

	/** Searchable arrays use the same annotation transfer path as strings. */
	@AssociatedData(localized = true, searchable = true, searchWeight = 5.5f)
	String[] getParagraphs();

	/** Complex associated data retains both its text expression and ranking weight. */
	@AssociatedData(localized = true, searchable = true, textExpression = "$value.title", searchWeight = 6.5f)
	Article getArticle();

	/** Serializable article projected into a complex data object. */
	record Article(String title) implements Serializable {}

	@Reference(managed = false)
	Brand getMarketingBrand();

	interface Brand extends Serializable {

		@ReferencedEntity
		int getBrand();

		@Attribute(localized = true, searchable = true, analyzer = FulltextAnalyzerSchemaContract.HTML_TEXT,
		searchWeight = 4.5f)
		String getLabel();

	}

}
