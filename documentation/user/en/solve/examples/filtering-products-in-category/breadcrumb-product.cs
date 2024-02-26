EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Product"),
        	FilterBy(
        		AttributeEquals("url", "/en/macbook-pro-13-2022"),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
        		EntityFetch(
        			AttributeContent("code", "name"),
        			ReferenceContent(
        				"categories",
        				EntityFetch(
        					AttributeContent("code", "name"),
        					HierarchyContent(
        						EntityFetch(
        							AttributeContent("code", "name", "level")
        						)
        					)
        				)
        			)
        		)
        	)
        )
	)
);