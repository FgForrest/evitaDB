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

package io.evitadb.performance.substring.state;

import io.evitadb.api.requestResponse.schema.FilterIndexCapability;

/**
 * The two arms of the substring A/B, which differ in **one schema flag and nothing else**.
 *
 * The corpus, the entity primary keys, the query and the predicate are identical on both sides; the only difference is
 * whether the `title` attribute declares {@link FilterIndexCapability#SUBSTRING}, which is what decides whether the
 * global entity index hosts a `TrigramIndex` for it at all. With no trigram index
 * `AbstractAttributeStringSearchTranslator` falls through to `FilterIndex#getRecordsWhoseValuesContains`, a scan over
 * every distinct value - so the arm names the *execution* being measured, not a different dataset.
 *
 * `SubstringCatalogFixture` asserts the arm actually took effect (a trigram index present on {@link #TRIGRAM} and
 * absent on {@link #SCAN}), because an arm that silently failed to configure itself would compare the scan with
 * itself and report a speedup of exactly one.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
public enum SubstringIndexArm {

	/**
	 * `title` is `filterable(FilterIndexCapability.SUBSTRING)`, so the attribute keeps a trigram index and
	 * `attributeContains` may be answered by candidate generation.
	 */
	TRIGRAM,

	/**
	 * `title` is plainly `filterable()`, so no trigram index exists and `attributeContains` scans every distinct
	 * value. This is evitaDB's behaviour before the substring index and the baseline every speedup is a ratio to.
	 */
	SCAN

}
