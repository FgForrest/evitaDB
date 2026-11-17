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

package io.evitadb.externalApi.configuration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.EXTERNAL_API;
import static io.evitadb.test.TestTags.SECURITY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies certificate defaults when JSON has no bundled configuration supplying the flag.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@DisplayName("Certificate options JSON defaults")
@Tag(EXTERNAL_API)
@Tag(SECURITY)
class CertificateOptionsTest {
	/** Verifies omitted, null and explicit flags through the real Jackson creator. */
	@ParameterizedTest(name = "{0} -> {1}")
	@DisplayName("Should generate self-signed certificates unless the JSON flag is explicitly false")
	@CsvSource(delimiter = '|', value = {
		"{}                                   | true",
		"{\"generateAndUseSelfSigned\":null}  | true",
		"{\"generateAndUseSelfSigned\":true}  | true",
		"{\"generateAndUseSelfSigned\":false} | false"
	})
	void shouldDefaultMissingFlagToTrueAndKeepExplicitValues(
		@Nonnull String json, boolean expected
	) throws JsonProcessingException {
		final CertificateOptions options = new ObjectMapper().readValue(json, CertificateOptions.class);

		assertEquals(expected, options.generateAndUseSelfSigned(), json);
	}
}
