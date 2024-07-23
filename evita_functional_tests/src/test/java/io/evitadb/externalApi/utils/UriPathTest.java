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

package io.evitadb.externalApi.utils;

import io.evitadb.exception.EvitaInternalError;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for {@link UriPath}.
 *
 * @author Lukáš Hornych, FG Forrest a.s. (c) 2023
 */
public class UriPathTest {

	@Test
	void shouldBuildUriPathFromArrayOfParts() {
		assertEquals("test1/test2", UriPath.of("test1", "test2").toString());
		assertEquals("test1/*", UriPath.of("test1", "*").toString());
		assertEquals("test1/{param1}", UriPath.of("test1", "{param1}").toString());
		assertEquals("/test1/{param1}", UriPath.of("/", "test1", "{param1}").toString());
		assertEquals("/test1/{param1}", UriPath.of("/test1", "{param1}").toString());
		assertEquals("/test1/test2/test3", UriPath.of("/test1", "/test2", "/test3").toString());
		assertEquals("test1/test2/test3", UriPath.of(UriPath.of("test1", "test2"), "test3").toString());
		assertEquals("test1/test2/test3", UriPath.of(UriPath.of("test1", "test2"), UriPath.of("test3")).toString());
		assertEquals("test1/test2/test3", UriPath.of("test1/test2", UriPath.of("test3")).toString());
	}

	@Test
	void shouldBuildUriPathFromBuilder() {
		assertEquals("test1/test2", UriPath.builder().part("test1").part("test2").build().toString());
		assertEquals("test1/*", UriPath.builder().part("test1").part("*").build().toString());
		assertEquals("test1/{param1}", UriPath.builder().part("test1").part("{param1}").build().toString());
		assertEquals("/test1/{param1}", UriPath.builder().part("/").part("test1").part("{param1}").build().toString());
		assertEquals("/test1/{param1}", UriPath.builder().part("/test1").part("{param1}").build().toString());
		assertEquals("/test1/test2/test3", UriPath.builder().part("/test1").part("/test2").part("/test3").build().toString());
		assertEquals("test1/test2/test3", UriPath.builder().part(UriPath.of("test1", "test2")).part("test3").build().toString());
		assertEquals("test1/test2/test3", UriPath.builder().part(UriPath.of("test1", "test2")).part(UriPath.of("test3")).build().toString());
		assertEquals("test1/test2/test3", UriPath.builder().part("test1/test2").part(UriPath.of("test3")).build().toString());
	}

	@Test
	void shouldNotBuildInvalidUriPath() {
		assertThrows(EvitaInternalError.class, () -> UriPath.of("test 1"));
	}
}
