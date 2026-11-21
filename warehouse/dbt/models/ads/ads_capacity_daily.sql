SELECT toUUID('{{ var("generation_id") }}') AS generation_id, *
FROM {{ ref('dws_capacity_daily') }}
WHERE day >= toDate(now(), 'Asia/Shanghai') - 89
{% if is_incremental() %}
UNION ALL
SELECT toUUID('{{ var("generation_id") }}') AS generation_id, * EXCEPT generation_id
FROM {{ this }} AS previous
WHERE previous.generation_id = (SELECT generation_id FROM {{ source('raw', 'publications') }} ORDER BY published_at DESC LIMIT 1)
    AND day < toDate(now(), 'Asia/Shanghai') - 89
    AND day >= toDate(now(), 'Asia/Shanghai') - 365
{% endif %}
