/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2023-2025
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

package io.evitadb.api.query.require;

import io.evitadb.utils.ArrayUtils;
import org.junit.jupiter.api.Test;

import static io.evitadb.api.query.QueryConstraints.accompanyingPriceContent;
import static org.junit.jupiter.api.Assertions.*;

/**
 * This tests verifies basic properties of {@link AccompanyingPriceContent} query.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
class AccompanyingPriceTest {

    @Test
    void shouldCreateDefaultViaFactoryClassWorkAsExpected() {
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent();
        assertEquals("default", accompanyingPrice.getAccompanyingPriceName().orElseThrow());
        assertArrayEquals(ArrayUtils.EMPTY_STRING_ARRAY, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpected() {
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent("myPrice", "basic", "reference");
        assertEquals("myPrice", accompanyingPrice.getAccompanyingPriceName().orElseThrow());
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullInArray() {
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent("myPrice", "basic", null, "reference");
        assertEquals("myPrice", accompanyingPrice.getAccompanyingPriceName().orElseThrow());
        assertArrayEquals(new String[] {"basic", "reference"}, accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedForNullVariable() {
        final String nullString = null;
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent(nullString);
        assertNull(accompanyingPrice);
    }

    @Test
    void shouldCreateViaFactoryClassWorkAsExpectedNullValueInArray() {
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent("myPrice", ArrayUtils.EMPTY_STRING_ARRAY);
        assertEquals("myPrice", accompanyingPrice.getAccompanyingPriceName().orElseThrow());
        assertArrayEquals(new String[0], accompanyingPrice.getPriceLists());
    }

    @Test
    void shouldRecognizeApplicability() {
        assertTrue(new AccompanyingPriceContent().isApplicable());
        assertTrue(accompanyingPriceContent("A").isApplicable());
        assertTrue(accompanyingPriceContent("A", "B").isApplicable());
        assertTrue(accompanyingPriceContent("A", "B", "C").isApplicable());
    }

    @Test
    void shouldToStringReturnExpectedFormat() {
        final AccompanyingPriceContent accompanyingPrice = accompanyingPriceContent("myPrice", "basic", "reference");
        assertEquals("accompanyingPriceContent('myPrice','basic','reference')", accompanyingPrice.toString());
    }

    @Test
    void shouldConformToEqualsAndHashContract() {
        assertNotSame(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("myPrice", "basic", "reference"));
        assertEquals(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("myPrice", "basic", "reference"));
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("myPrice", "basic", "action"));
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("myPrice", "basic"));
        assertEquals(accompanyingPriceContent("myPrice", "basic", "reference").hashCode(), accompanyingPriceContent("myPrice", "basic", "reference").hashCode());
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference").hashCode(), accompanyingPriceContent("myPrice", "basic", "action").hashCode());
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference").hashCode(), accompanyingPriceContent("myPrice", "basic").hashCode());
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("otherPrice", "basic", "reference"));
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference").hashCode(), accompanyingPriceContent("otherPrice", "basic", "reference").hashCode());
        assertNotEquals(accompanyingPriceContent(), accompanyingPriceContent("myPrice", "basic", "reference"));
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference"), accompanyingPriceContent("myPrice2", "basic", "reference"));
        assertNotEquals(accompanyingPriceContent().hashCode(), accompanyingPriceContent("myPrice", "basic", "reference").hashCode());
        assertNotEquals(accompanyingPriceContent("myPrice", "basic", "reference").hashCode(), accompanyingPriceContent("myPrice2", "basic", "reference").hashCode());
        assertEquals(accompanyingPriceContent(), accompanyingPriceContent());
        assertEquals(accompanyingPriceContent().hashCode(), accompanyingPriceContent().hashCode());

    }

}
