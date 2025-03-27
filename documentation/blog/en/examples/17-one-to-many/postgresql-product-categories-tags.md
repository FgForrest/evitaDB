```sql
WITH RECURSIVE category_tree AS (
    -- Base case: Get all top-level categories (where parentCategoryId is NULL)
    SELECT
        id,
        parentCategoryId,
        name,
        1 AS depth
    FROM
        Category
    WHERE
        parentCategoryId IS NULL
    UNION ALL
    -- Recursive case: Get child categories, increment depth
    SELECT
        c.id,
        c.parentCategoryId,
        c.name,
        ct.depth + 1 AS depth
    FROM
        Category c
    JOIN category_tree ct ON c.parentCategoryId = ct.id
),
category_with_priority AS (
    -- Join categories with their associated tags and get the highest priority tag for each category
    SELECT
        c.id AS category_id,
        c.name AS category_name,
        ct.depth,
        t.priority,
        ROW_NUMBER() OVER (PARTITION BY c.id ORDER BY t.priority DESC) AS rn
    FROM
        Category c
    LEFT JOIN CategoryTags ctg ON c.id = ctg.categoryId
    LEFT JOIN Tags t ON ctg.tagId = t.id
)
SELECT
    p.id AS product_id,
    p.name AS product_name,
    c.id AS category_id,
    c.name AS category_name,
    ct.depth,
    pc.order
FROM
    category_tree ct
JOIN ProductCategory pc ON pc.categoryId = ct.id
JOIN Product p ON p.id = pc.productId
-- Ensure categories are ordered by priority of first assigned tag
JOIN category_with_priority c ON ct.id = c.category_id
WHERE
    c.rn = 1 -- Only consider the highest priority tag for each category
ORDER BY
    ct.depth,         -- Order categories by depth (depth-first traversal)
    c.priority DESC,  -- Order categories at the same depth by highest priority tag
    pc.order;         -- Order products by 'order' in ProductCategory
```