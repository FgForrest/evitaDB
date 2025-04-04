final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					and(
						entityPrimaryKeyInSet(1, 2, 3),
						attributeEquals("status", "ACTIVE")
					)
				),
				orderBy(
					attributeNatural("code", ASC),
					attributeNatural("catalogNumber", DESC)
				),
				require(
					entityFetch(
						attributeContentAll(),
						priceContentAll()
					),
					facetSummary(COUNTS)
				)
			)
		);
	}
);
