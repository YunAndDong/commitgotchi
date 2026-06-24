CREATE TEMP TABLE character_catalog_reseed_map (
    old_id BIGINT PRIMARY KEY,
    new_id BIGINT NOT NULL UNIQUE
) ON COMMIT DROP;

INSERT INTO character_catalog_reseed_map (old_id, new_id)
SELECT reserved.id,
       (SELECT GREATEST(COALESCE(MAX(id), 0), 3) FROM characters)
           + ROW_NUMBER() OVER (ORDER BY reserved.id)
FROM characters reserved
WHERE reserved.id IN (1, 2, 3)
  AND (reserved.personality IS NOT NULL OR reserved.design_keyword IS NOT NULL);

INSERT INTO characters (
    id,
    personality,
    design_keyword,
    sprite_sheet_url,
    sprite_meta,
    image_status,
    created_at
)
SELECT reseed.new_id,
       original.personality,
       original.design_keyword,
       original.sprite_sheet_url,
       original.sprite_meta,
       original.image_status,
       original.created_at
FROM characters original
JOIN character_catalog_reseed_map reseed ON reseed.old_id = original.id;

UPDATE user_character
SET character_id = reseed.new_id
FROM character_catalog_reseed_map reseed
WHERE user_character.character_id = reseed.old_id;

DELETE FROM characters
USING character_catalog_reseed_map reseed
WHERE characters.id = reseed.old_id;

INSERT INTO characters (
    id,
    personality,
    design_keyword,
    sprite_sheet_url,
    sprite_meta,
    image_status
)
VALUES
    (
        1,
        NULL,
        NULL,
        regexp_replace('${characterImageS3ObjectPrefix}', '/+$', '') || '/characters/1/sprite-sheet.png',
        '{"columns":3,"rows":1,"frameMap":{"joy":[0,0],"sad":[0,1],"angry":[0,2]},"transparent":true}'::jsonb,
        'READY'
    ),
    (
        2,
        NULL,
        NULL,
        regexp_replace('${characterImageS3ObjectPrefix}', '/+$', '') || '/characters/2/sprite-sheet.png',
        '{"columns":3,"rows":1,"frameMap":{"joy":[0,0],"sad":[0,1],"angry":[0,2]},"transparent":true}'::jsonb,
        'READY'
    ),
    (
        3,
        NULL,
        NULL,
        regexp_replace('${characterImageS3ObjectPrefix}', '/+$', '') || '/characters/3/sprite-sheet.png',
        '{"columns":3,"rows":1,"frameMap":{"joy":[0,0],"sad":[0,1],"angry":[0,2]},"transparent":true}'::jsonb,
        'READY'
    )
ON CONFLICT (id) DO UPDATE
SET personality = EXCLUDED.personality,
    design_keyword = EXCLUDED.design_keyword,
    sprite_sheet_url = EXCLUDED.sprite_sheet_url,
    sprite_meta = EXCLUDED.sprite_meta,
    image_status = EXCLUDED.image_status;

SELECT setval(
    pg_get_serial_sequence('characters', 'id'),
    GREATEST((SELECT COALESCE(MAX(id), 0) FROM characters), 3),
    true
);
