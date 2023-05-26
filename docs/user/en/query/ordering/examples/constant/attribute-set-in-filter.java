final EvitaResponse<SealedEntity> entities = evita.queryCatalog(
	"evita",
	session -> {
		return session.querySealedEntity(
			query(
				collection("Product"),
				filterBy(
					attributeInSet(
						"code", 
						"msi-gs66-10sf-stealth-1", 
						"apple-iphone-14-plus", 
						"lenovo-thinkpad-p14s-5"
					)
				),
				orderBy(
					attributeSetInFilter("code")
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