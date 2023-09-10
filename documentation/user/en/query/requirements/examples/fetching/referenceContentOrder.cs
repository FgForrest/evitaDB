EvitaResponse<SealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		EntityPrimaryKeyInSet(103885),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("name"),
        			ReferenceContent(
        				"parameterValues",
        				OrderBy(
        					EntityProperty(
        						AttributeNatural("name", Asc)
        					)
        				),
        				EntityFetch(
        					AttributeContent("name")
        				)
        			)
        		)
        	)
        )
	)
);