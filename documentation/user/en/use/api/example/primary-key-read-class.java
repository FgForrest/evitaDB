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
 *   https://github.com/FgForrest/evitaDB/blob/master/LICENSE
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

@EntityRef("Product")
@Data
public class MyEntity {
	@PrimaryKey private final int id;
	@PrimaryKey private final Integer idAsIntegerObject;
	@PrimaryKey private final long idAsLong;
	@PrimaryKey private final Long idAsLongObject;

	@PrimaryKeyRef private final int idRef;
	@PrimaryKeyRef private final Integer idRefAsIntegerObject;
	@PrimaryKeyRef private final long idRefAsLong;
	@PrimaryKeyRef private final Long idRefAsLongObject;

	// constructor is usually generated by Lombok
	public MyEntity(
		int id, Integer idAsIntegerObject, long idAsLong, Long idAsLongObject,
		int idRef, Integer idRefAsIntegerObject, long idRefAsLong, Long idRefAsLongObject
	) {
		this.id = id;
		this.idAsIntegerObject = idAsIntegerObject;
		this.idAsLong = idAsLong;
		this.idAsLongObject = idAsLongObject;
		this.idRef = idRef;
		this.idRefAsIntegerObject = idRefAsIntegerObject;
		this.idRefAsLong = idRefAsLong;
		this.idRefAsLongObject = idRefAsLongObject;
	}

}
