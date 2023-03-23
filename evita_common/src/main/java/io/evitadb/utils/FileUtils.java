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

package io.evitadb.utils;

import io.evitadb.exception.UnexpectedIOException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * FileUtils contains various utility methods for work with file system.
 *
 * We know these functions are available in Apache Commons, but we try to keep our transitive dependencies as low as
 * possible, so we rather went through duplication of the code.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class FileUtils {

	/**
	 * Returns list of folders in Evita directory. Each folder is considered to be Evita catalog - name of the folder
	 * must be the name of catalog itself.
	 */
	@Nonnull
	public static Path[] listDirectories(@Nonnull Path directory) {
		if (directory.toFile().exists()) {
			try (final DirectoryStream<Path> dirStream = Files.newDirectoryStream(directory, entry -> entry.toFile().isDirectory())) {
				return StreamSupport.stream(dirStream.spliterator(), false).toArray(Path[]::new);
			} catch (IOException ex) {
				throw new UnexpectedIOException(
					"Failed to read directory: " + directory,
					"Failed to read directory!", ex
				);
			}
		} else {
			return new Path[0];
		}
	}

	/**
	 * Method deletes directory along with its contents.
	 */
	public static void deleteDirectory(@Nonnull Path directory) {
		if (directory.toFile().exists()) {
			try (final Stream<Path> stream = Files.list(directory)) {
				stream.forEach(it -> {
					if (it.toFile().isDirectory()) {
						deleteDirectory(it);
					} else {
						if (!it.toFile().delete()) {
							throw new UnexpectedIOException(
								"Failed to delete file: " + it,
								"Failed to delete file!"
							);
						}
					}
				});

				if (!directory.toFile().delete()) {
					throw new UnexpectedIOException(
						"Failed to delete directory: " + directory,
						"Failed to delete directory!"
					);
				}
			} catch (IOException ex) {
				throw new UnexpectedIOException(
					"Failed to delete directory: " + directory,
					"Failed to delete directory!", ex
				);
			}
		}
	}
}
