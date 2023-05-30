final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeBetween("battery-capacity", 125, 160)
				),
				require(
					entityFetch(
						attributeContent("code", "battery-capacity")
					)
				)
			)
		);
	}
);