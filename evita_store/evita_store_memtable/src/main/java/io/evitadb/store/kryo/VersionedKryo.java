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

package io.evitadb.store.kryo;

import com.esotericsoftware.kryo.ClassResolver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.ReferenceResolver;
import io.evitadb.store.fileOffsetIndex.FileOffsetIndex;
import lombok.Getter;

/**
 * This class overrides basic {@link Kryo} implementation and adds information about the version
 * of the {@link FileOffsetIndex.MemTableKryoPool} that was used for creating those instances.
 * Version serves to safely discard all instances once they become obsolete.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2021
 */
public class VersionedKryo extends Kryo {
	@Getter private final long version;

	static {
		System.setProperty("kryo.unsafe", "false");
	}

	public VersionedKryo(long version) {
		this.version = version;
	}

	public VersionedKryo(long version, ReferenceResolver referenceResolver) {
		super(referenceResolver);
		this.version = version;
	}

	public VersionedKryo(long version, ClassResolver classResolver, ReferenceResolver referenceResolver) {
		super(classResolver, referenceResolver);
		this.version = version;
	}

}
