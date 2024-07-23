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

package io.evitadb.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * This test verifies {@link NamingConvention}.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2022
 */
class NamingConventionTest {

	@Test
	void shouldGenerateVariants() {
		final Map<NamingConvention, String> variants = NamingConvention.generate("Zluty_kunPel-ody~@01");
		assertEquals(NamingConvention.values().length, variants.size());
		assertEquals("ZlutyKunPelOdy01", variants.get(NamingConvention.PASCAL_CASE));
		assertEquals("zlutyKunPelOdy01", variants.get(NamingConvention.CAMEL_CASE));
		assertEquals("zluty_kun_pel_ody_01", variants.get(NamingConvention.SNAKE_CASE));
		assertEquals("ZLUTY_KUN_PEL_ODY_01", variants.get(NamingConvention.UPPER_SNAKE_CASE));
		assertEquals("zluty-kun-pel-ody-01", variants.get(NamingConvention.KEBAB_CASE));
	}

}
