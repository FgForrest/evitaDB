EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Brand"),
        	FilterBy(
        		EntityPrimaryKeyInSet(64703),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "name")
        		)
        	)
        )
	)
);