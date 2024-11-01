final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				orderBy(
					randomWithSeed(42)
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