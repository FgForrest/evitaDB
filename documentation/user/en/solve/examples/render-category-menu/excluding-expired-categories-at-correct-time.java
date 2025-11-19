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
									OffsetDateTime.parse("2023-12-05T12:00:00Z", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
								)
							)
						)
					)
				),
				require(
					hierarchyOfReference(
						"categories",
						fromRoot(
							"topLevel",
							entityFetch(
								attributeContent("code")
							),
							stopAt(
								level(2)
							)
						)
					)
				)
			)
		);
	}
);
