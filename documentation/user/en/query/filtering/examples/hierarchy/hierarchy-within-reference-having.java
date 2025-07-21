final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "accessories"),
						having(
							or(
								attributeIs("validity", NULL),
								attributeInRange(
									"validity", 
									OffsetDateTime.parse("2023-10-01T01:00:00-01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
								)
							)
						)
					)
				),
				require(
					entityFetch(
						attributeContent("code")
					)
				)
			)
		);
	}
);