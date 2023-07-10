final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeInRange(
						"validity", 
						OffsetDateTime.parse("2023-12-05T12:00:00+01:00", DateTimeFormatter.ISO_OFFSET_DATE_TIME)
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