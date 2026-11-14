/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024-2025
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

package io.evitadb.api.exception;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.annotation.Nonnull;

import static io.evitadb.test.TestTags.CONTRACT;
import static io.evitadb.test.TestTags.EXPORT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exact operator-facing messages for both export backend properties.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Tag(CONTRACT)
@Tag(EXPORT)
@DisplayName("Export file too large exception")
class ExportFileTooLargeExceptionTest {
	@Nested
	@DisplayName("Operator message")
	class OperatorMessage {
		/** Recording subjects share the same operator guidance without the backup's catalog-size sentence. */
		@ParameterizedTest
		@ValueSource(strings = {
			"The JFR recording", "The traffic recording of catalog `X`", "The traffic recording export of catalog `X`"
		})
		@DisplayName("should name each recording subject and backend configuration")
		void shouldNameRecordingSubjectAndConfiguration(@Nonnull String subject) {
			final ExportFileTooLargeException exception = new ExportFileTooLargeException(
				subject, 1073741824L, "export.fileSystem.sizeLimitBytes");
			assertEquals(subject + " was stopped because it exceeded the export storage limit of " +
				"1.00 GB; the server would delete a larger file. No recording was created. Increase " +
				"`export.fileSystem.sizeLimitBytes` in the server configuration " +
				"(or `EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server.",
				exception.getPublicMessage());
			assertEquals(exception.getMessage(), exception.getPublicMessage());
		}

		@Test
		@DisplayName("should include the file system configuration and catalog size")
		void shouldIncludeFileSystemConfigurationAndSize() {
			final ExportFileTooLargeException exception = new ExportFileTooLargeException(
				"X", 1073741824L, "export.fileSystem.sizeLimitBytes", 7945689498L
			);
			assertEquals("The backup of catalog `X` was stopped because it exceeded the export storage limit of " +
				"1.00 GB; the server would delete a larger file. No backup was created. Increase " +
				"`export.fileSystem.sizeLimitBytes` in the server configuration (or " +
				"`EVITADB_EXPORT_FILESYSTEM_SIZELIMITBYTES`) and restart the server. " +
				"The catalog currently occupies 7.40 GB on disk.", exception.getPublicMessage());
			assertEquals(exception.getMessage(), exception.getPublicMessage());
		}

		@Test
		@DisplayName("should omit the size sentence when measurement is unavailable")
		void shouldOmitUnavailableSizeAndNameS3Configuration() {
			final ExportFileTooLargeException exception = new ExportFileTooLargeException(
				"X", 1073741824L, "export.s3.sizeLimitBytes", null
			);
			assertEquals("The backup of catalog `X` was stopped because it exceeded the export storage limit of " +
				"1.00 GB; the server would delete a larger file. No backup was created. Increase " +
				"`export.s3.sizeLimitBytes` in the server configuration (or " +
				"`EVITADB_EXPORT_S3_SIZELIMITBYTES`) and restart the server.", exception.getPublicMessage());
			assertEquals(exception.getMessage(), exception.getPublicMessage());
		}
	}
}
