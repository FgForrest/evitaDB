final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				head(
					collection("Product"),
					label("query-name", "my-query")
				),
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