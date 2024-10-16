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

import io.evitadb.exception.UnexpectedIOException;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
	private static final Path[] EMPTY_PATHS = new Path[0];
	private static final Pattern WHITESPACE_FILE_NAME_CHARACTERS_PATTERN = Pattern.compile("\\s+");
	private static final Pattern UNSUPPORTED_FILE_NAME_CHARACTERS_PATTERN = Pattern.compile("[+<>:\"/\\\\|?*]+");
	private static final Pattern COLLAPSE_PATTERN = Pattern.compile("[_-]{2,}");

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
			return EMPTY_PATHS;
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

	/**
	 * Checks whether the directory is empty or contains any file.
	 *
	 * @param path Path to directory
	 * @return True if directory is empty, false otherwise
	 */
	public static boolean isDirectoryEmpty(@Nonnull Path path) {
		try (final DirectoryStream<Path> dirStream = Files.newDirectoryStream(path, entry -> entry.toFile().isFile())) {
			return !dirStream.iterator().hasNext();
		} catch (IOException ex) {
			throw new UnexpectedIOException(
				"Failed to read directory: " + path,
				"Failed to read directory!", ex
			);
		}
	}

	/**
	 * Moves a source file to a target file, replacing the target file if it already exists.
	 * This method ensures atomic move if supported by the underlying file system.
	 *
	 * @param sourceFile the path of the source file to be moved
	 * @param targetFile the path of the target file where the source file should be moved to
	 * @throws UnexpectedIOException if an unexpected I/O error occurs during the file movement
	 */
	public static void rewriteTargetFileAtomically(
		@Nonnull Path sourceFile,
		@Nonnull Path targetFile
	) {
		try {
			Files.move(
				sourceFile, targetFile,
				StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
			);
		} catch (AtomicMoveNotSupportedException e) {
			try {
				Files.move(
					sourceFile, targetFile,
					StandardCopyOption.REPLACE_EXISTING
				);
			} catch (Exception fallbackException) {
				throw new UnexpectedIOException(
					"Failed to move temporary bootstrap file to the original location!",
					"Failed to move temporary bootstrap file!",
					fallbackException
				);
			}
		} catch (Exception e) {
			throw new UnexpectedIOException(
				"Failed to move temporary bootstrap file to the original location!",
				"Failed to move temporary bootstrap file!",
				e
			);
		}
	}

	/**
	 * Deletes the specified folder if it is empty.
	 *
	 * @param parentDirectory The path to the parent directory.
	 * @throws UnexpectedIOException If the empty folder cannot be deleted.
	 */
	public static void deleteFolderIfEmpty(@Nonnull Path parentDirectory) {
		try {
			if (parentDirectory.toFile().exists()) {
				try (final Stream<Path> fileStream = Files.list(parentDirectory)) {
					if (fileStream.findFirst().isEmpty()) {
						Files.delete(parentDirectory);
					}
				}
			}
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Cannot delete empty folder: " + parentDirectory,
				"Cannot delete empty folder!",
				e
			);
		}
	}

	/**
	 * Deletes the specified file.
	 *
	 * @param file The path to the file to be deleted.
	 * @throws UnexpectedIOException If an I/O error occurs during file deletion.
	 */
	public static void deleteFile(@Nonnull Path file) {
		try {
			Files.delete(file);
		} catch (IOException e) {
			throw new UnexpectedIOException(
				"Cannot delete file: " + file,
				"Cannot delete file!",
				e
			);
		}
	}

	/**
	 * Returns the size of the specified directory in bytes.
	 *
	 * @param directory The path to the directory.
	 * @return The size of the directory in bytes.
	 */
	public static long getDirectorySize(@Nonnull Path directory) {
		// calculate size of all bytes in particular directory
		Exception ex = null;
		// we implement a retry logic - the walker fails to calculate folder size when file is removed during the walk
		for (int i = 0; i < 5; i++) {
			try (final Stream<Path> walk = Files.walk(directory);) {
				return walk
					.filter(Files::isRegularFile)
					.mapToLong(it -> {
						try {
							return Files.size(it);
						} catch (IOException e) {
							throw new UnexpectedIOException(
								"Failed to get size of file: " + it,
								"Failed to get size of file!", e
							);
						}
					})
					.sum();
			} catch (UnexpectedIOException | IOException | UncheckedIOException e) {
				ex = e;
			}
		}

		throw new UnexpectedIOException(
			"Failed to calculate size of directory: " + directory,
			"Failed to calculate size of directory!", ex
		);
	}

	/**
	 * Returns the extension of the file.
	 *
	 * @param fileName The name of the file.
	 * @return The extension of the file.
	 */
	@Nonnull
	public static Optional<String> getFileExtension(@Nonnull String fileName) {
		final int i = fileName.lastIndexOf('.') + 1;
		return i > 0 ? Optional.of(fileName.substring(i)).filter(it -> !it.isBlank()) : Optional.empty();
	}

	/**
	 * Returns the file name without its extension.
	 *
	 * @param name the full name of the file, including its extension
	 * @return the name of the file without the extension
	 */
	@Nonnull
	public static String getFileNameWithoutExtension(@Nonnull String name) {
		final int i = name.lastIndexOf('.');
		return i > 0 ? name.substring(0, i) : name;
	}

	/**
	 * Converts name with potentially unsupported characters to a name that is supported by all file systems.
	 *
	 * @param name The name to convert.
	 * @return The name with unsupported characters replaced by a dash.
	 */
	@Nonnull
	public static String convertToSupportedName(@Nonnull String name) {
		return Arrays.stream(
				name.split("\\.", 2)
			)
			.map(String::trim)
			.map(it -> WHITESPACE_FILE_NAME_CHARACTERS_PATTERN.matcher(it).replaceAll("_"))
			.map(it -> UNSUPPORTED_FILE_NAME_CHARACTERS_PATTERN.matcher(it).replaceAll("-"))
			.map(it -> COLLAPSE_PATTERN.matcher(it).replaceAll("-"))
			.collect(Collectors.joining("."));
	}

	/**
	 * Retrieves the last modified time of the specified file.
	 *
	 * @param pathToFile the path to the file
	 * @return an Optional containing the last modified time of the file as an OffsetDateTime, or an empty Optional if an IOException occurs
	 */
	@Nonnull
	public static Optional<OffsetDateTime> getFileLastModifiedTime(@Nonnull Path pathToFile) {
		try {
			return Optional.of(
				OffsetDateTime.ofInstant(
					Files.getLastModifiedTime(pathToFile).toInstant(),
					OffsetDateTime.now().getOffset()
				)
			);
		} catch (IOException e) {
			return Optional.empty();
		}
	}
}
