/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023
 *
 *   Licensed under the Business Source License, Version 1.1 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *   https://github.com/FgForrest/evitaDB/blob/main/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package io.evitadb.externalApi.rest.api.catalog.dataApi.builder.constraint;

import io.evitadb.api.query.require.Require;
import io.evitadb.externalApi.rest.api.catalog.dataApi.model.ParamDescriptor;
import io.evitadb.externalApi.rest.api.catalog.dataApi.resolver.constraint.RequireConstraintFromRequestQueryBuilder;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Description
 *
 * @author Martin Veska (veska@fg.cz), FG Forrest a.s. (c) 2022
 */
class RequireQueryConstraintsBuilderTest {

	@Test
	void shouldBuildRequireToFetchAll() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.FETCH_ALL.name(), Boolean.TRUE));
		assertEquals("require(entityFetch(attributeContent(),associatedDataContent(),priceContent(ALL),referenceContent(),dataInLocales()))", require.toString());

		assertNull(RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.FETCH_ALL.name(), Boolean.FALSE)));
	}

	@Test
	void shouldBuildRequireToFetchEntityBody() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.BODY_FETCH.name(), Boolean.TRUE));
		assertEquals("require(entityFetch())", require.toString());

		assertNull(RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.BODY_FETCH.name(), Boolean.FALSE)));
	}

	@Test
	void shouldBuildRequireToFetchAllAttributes() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.TRUE));
		assertEquals("require(entityFetch(attributeContent()))", require.toString());

		assertNull(RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ATTRIBUTE_CONTENT_ALL.name(), Boolean.FALSE)));
	}

	@Test
	void shouldBuildRequireToFetchSelectedAttributes() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ATTRIBUTE_CONTENT.name(), new String[]{"first", "second"}));
		assertEquals("require(entityFetch(attributeContent('first','second')))", require.toString());
	}

	@Test
	void shouldBuildRequireToFetchAllAssociatedData() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.TRUE));
		assertEquals("require(entityFetch(associatedDataContent()))", require.toString());

		assertNull(RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ASSOCIATED_DATA_CONTENT_ALL.name(), Boolean.FALSE)));
	}

	@Test
	void shouldBuildRequireToFetchAssociatedData() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.ASSOCIATED_DATA_CONTENT.name(), new String[]{"third"}));
		assertEquals("require(entityFetch(associatedDataContent('third')))", require.toString());
	}

	@Test
	void shouldBuildRequireToFetchAllReferences() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.REFERENCE_CONTENT_ALL.name(), Boolean.TRUE));
		assertEquals("require(entityFetch(referenceContent()))", require.toString());

		assertNull(RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.REFERENCE_CONTENT_ALL.name(), Boolean.FALSE)));
	}

	@Test
	void shouldBuildRequireToFetchReferences() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.REFERENCE_CONTENT.name(), new String[]{"fourth","fifth"}));
		assertEquals("require(entityFetch(referenceContent('fourth','fifth')))", require.toString());
	}

	@Test
	void shouldBuildRequireToSetLocales() {
		final Require require = RequireConstraintFromRequestQueryBuilder.buildRequire(Collections.singletonMap(ParamDescriptor.DATA_IN_LOCALES.name(), new Locale[]{new Locale("cs","CZ")}));
		assertEquals("require(entityFetch(dataInLocales('cs-CZ')))", require.toString());
	}
}