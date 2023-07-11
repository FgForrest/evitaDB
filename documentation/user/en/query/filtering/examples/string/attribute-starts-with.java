final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeStartsWith("code", "garmin")
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