EvitaResponse<ISealedEntity> entities = evita.QueryCatalog(
	"evita",
	session => session.QuerySealedEntity(
        Query(
        	Collection("Category"),
        	FilterBy(
        		AttributeEquals("url", "/en/smartwatches"),
        		EntityLocaleEquals(CultureInfo.GetCultureInfo("en"))
        	),
        	Require(
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
);