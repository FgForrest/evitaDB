/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2026
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

package io.evitadb.externalApi.observability.metric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static io.evitadb.test.TestTags.OBSERVABILITY;
import static io.evitadb.test.TestTags.OBSERVABILITY_API;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link QueryLabelBag#extractValue(String, String)} - the lookup that surfaces the value of one configured
 * query label out of the comma-delimited `name=value` bag carried by each query event. The "not carried" cases matter
 * most: they are what makes the exported dimension render as `N/A` when a query simply didn't tag itself with that
 * label.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("QueryLabelBag extraction")
@Tag(OBSERVABILITY_API)
@Tag(OBSERVABILITY)
class QueryLabelBagTest {

	private static final String BAG = "job_name=feed-job,rest_method=GET /products,tenant=acme";

	@Test
	@DisplayName("Should extract a label's value from any position in the bag")
	void shouldExtractFromAnyPosition() {
		assertEquals("feed-job", QueryLabelBag.extractValue(BAG, "job_name"));         // first
		assertEquals("GET /products", QueryLabelBag.extractValue(BAG, "rest_method")); // middle
		assertEquals("acme", QueryLabelBag.extractValue(BAG, "tenant"));               // last
	}

	@Test
	@DisplayName("Should return null when the bag does not carry the requested label")
	void shouldReturnNullForAbsentLabel() {
		assertNull(QueryLabelBag.extractValue(BAG, "missing"));
		// a prefix of an existing name must not match
		assertNull(QueryLabelBag.extractValue(BAG, "job"));
		// a suffix of an existing name must not match either
		assertNull(QueryLabelBag.extractValue(BAG, "name"));
	}

	@Test
	@DisplayName("Should return null for an empty or null bag")
	void shouldReturnNullForEmptyBag() {
		assertNull(QueryLabelBag.extractValue(null, "job_name"));
		assertNull(QueryLabelBag.extractValue("", "job_name"));
	}

	@Test
	@DisplayName("Should keep everything after the first '=' as the value")
	void shouldKeepValueAfterFirstEquals() {
		assertEquals("a=b=c", QueryLabelBag.extractValue("expr=a=b=c,tenant=acme", "expr"));
	}

	@Test
	@DisplayName("Should extract a single-pair bag with no trailing delimiter")
	void shouldExtractSinglePair() {
		assertEquals("feed", QueryLabelBag.extractValue("job_name=feed", "job_name"));
	}

}
