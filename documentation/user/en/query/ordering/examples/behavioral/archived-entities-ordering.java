final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					scope(LIVE, ARCHIVED),
					attributeInSet(
						"code",
						"alcatel-1s",
						"motorola-edge",
						"apple-iphone-14",
						"alcatel-1",
						"nokia-c2"
					),
					entityLocaleEquals(Locale.forLanguageTag("en"))
				),
				orderBy(
					inScope(
						LIVE,
						attributeNatural("code", DESC)
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
