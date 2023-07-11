final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeGreaterThanEquals("battery-life", "40")
				),
				require(
					entityFetch(
						attributeContent("code", "battery-life")
					)
				)
			)
		);
	}
);