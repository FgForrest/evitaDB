/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2024
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

package io.evitadb.api;

import io.evitadb.api.requestResponse.schema.mutation.LocalCatalogSchemaMutation;

import javax.annotation.Nonnull;

/**
 * This extension interface to the {@link SchemaPostProcessor} allows to capture final set of mutations which are going
 * to be applied to the catalog.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2023
 */
public interface SchemaPostProcessorCapturingResult extends SchemaPostProcessor {

	/**
	 * The array of mutations that are going to be applied to the catalog.
	 *
	 * @param mutations array of mutations (do not modify in this method)
	 */
	void captureResult(@Nonnull LocalCatalogSchemaMutation[] mutations);

}
