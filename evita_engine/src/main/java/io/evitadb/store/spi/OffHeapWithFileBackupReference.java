/*
 *
 *                         _ _        ____  ____
 *               _____   _(_) |_ __ _|  _ \| __ )
 *              / _ \ \ / / | __/ _` | | | |  _ \
 *             |  __/\ V /| | || (_| | |_| | |_) |
 *              \___| \_/ |_|\__\__,_|____/|____/
 *
 *   Copyright (c) 2024
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

package io.evitadb.store.spi;

import lombok.Getter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * TODO JNO - document me
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2024
 */
public final class OffHeapWithFileBackupReference {

	private final Path filePath;
	private final ByteBuffer buffer;
	@Getter private final int contentLength;

	private OffHeapWithFileBackupReference(@Nullable Path filePath, @Nullable ByteBuffer buffer, int contentLength) {
		this.filePath = filePath;
		this.buffer = buffer;
		this.contentLength = contentLength;
	}

	@Nonnull
	public static OffHeapWithFileBackupReference withFilePath(@Nonnull Path filePath, int contentLength) {
		return new OffHeapWithFileBackupReference(Objects.requireNonNull(filePath), null, contentLength);
	}

	@Nonnull
	public static OffHeapWithFileBackupReference withByteBuffer(@Nonnull ByteBuffer buffer, int bufferPeak) {
		return new OffHeapWithFileBackupReference(null, Objects.requireNonNull(buffer), bufferPeak);
	}

	@Nonnull
	public Optional<Path> getFilePath() {
		return Optional.ofNullable(filePath);
	}

	@Nonnull
	public Optional<ByteBuffer> getBuffer() {
		return Optional.ofNullable(buffer);
	}

}
