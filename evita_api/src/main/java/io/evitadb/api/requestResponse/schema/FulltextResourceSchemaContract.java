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


package io.evitadb.api.requestResponse.schema;

import io.evitadb.api.requestResponse.schema.analysis.AnalysisSteps;
import io.evitadb.api.requestResponse.schema.analysis.FulltextResourceContent;

import javax.annotation.Nonnull;
import java.io.Serializable;

/**
 * A named piece of data that fulltext analysis steps read - a stop list, a list of protected words or a list of
 * synonym groups - declared in the catalog schema with
 * {@link CatalogSchemaEditor#withFulltextResource(String, FulltextResourceContent)}.
 *
 * Steps refer to a resource by name through {@link AnalysisSteps#resource(String)}, so one list can serve several
 * analyzers and is edited in one place. The built-in stop lists ({@link AnalysisSteps#BUILT_IN_RESOURCES}) are
 * resources too, read-only and never stored in the schema.
 */
public interface FulltextResourceSchemaContract extends Serializable {

	/**
	 * Returns the name steps refer to the resource by.
	 *
	 * @return the name, unique among the resources of the catalog
	 */
	@Nonnull
	String getName();

	/**
	 * Returns the content of the resource.
	 *
	 * @return the content - a word list or a synonym list
	 */
	@Nonnull
	FulltextResourceContent getContent();

}
