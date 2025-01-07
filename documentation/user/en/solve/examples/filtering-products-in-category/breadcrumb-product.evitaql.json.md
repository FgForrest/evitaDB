```json
{
  "data" : [
    {
      "primaryKey" : 63049,
      "attributes" : {
        "name:en" : "Macbook Pro 13 2022",
        "code" : "macbook-pro-13-2022"
      },
      "references" : {
        "categories" : [
          {
            "referencedKey" : 66470,
            "referencedEntity" : {
              "primaryKey" : 66470,
              "attributes" : {
                "name:en" : "Products in preparation",
                "code" : "prepared-products"
              }
            }
          },
          {
            "referencedKey" : 66479,
            "referencedEntity" : {
              "primaryKey" : 66479,
              "parent" : 66467,
              "parentEntity" : {
                "primaryKey" : 66467,
                "attributes" : {
                  "name:en" : "Laptops",
                  "code" : "laptops",
                  "level" : 1
                }
              },
              "attributes" : {
                "name:en" : "Macbooks",
                "code" : "macbooks"
              }
            }
          }
        ]
      }
    }
  ],
  "first" : true,
  "last" : true,
  "lastPageItemNumber" : 1,
  "lastPageNumber" : 1,
  "pageNumber" : 1,
  "pageSize" : 20,
  "singlePage" : true,
  "totalRecordCount" : 1
}
```