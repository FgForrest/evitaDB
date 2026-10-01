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

package io.evitadb.annotation;

/**
 * Marked type that {@link ClassFileReaderFixtures.Referencing} names **only** as
 * `new InternalArrayElementFixture[1][1]`, so its sole appearance in that class's constant pool is the descriptor
 * `[[Lio/evitadb/annotation/InternalArrayElementFixture;` - the one shape that reaches the reader's array
 * stripping on a real class file.
 *
 * It is a **top-level** class on purpose. As a nested one it would also be listed in the referencing class's
 * `InnerClasses` attribute, which carries a `CONSTANT_Class` entry of its own for the bare name - and the test would
 * then pass just as happily with the array stripping removed, asserting nothing.
 *
 * @author Jan Novotný (novotny@fg.cz), FG Forrest a.s. (c) 2026
 */
@Internal("use a supported fixture - this type exists to be reached through an array descriptor")
class InternalArrayElementFixture {
}
