final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					hierarchyWithin(
						"categories",
						attributeEquals("code", "vouchers-for-shareholders")
					),
					entityLocaleEquals(Locale.forLanguageTag("en-US"))
				),
				require(
					entityFetch(
						attributeContent("code", "name")
					)
				)
			)
		);
	}
);