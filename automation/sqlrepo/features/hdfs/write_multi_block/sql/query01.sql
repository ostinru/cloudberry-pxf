-- Write 32 million rows through PXF before reading the resulting files.
INSERT INTO pxf_multi_block_write
    SELECT format('t%s', i::varchar(255)), i
        FROM generate_series(1, 32000000) s(i);

-- @description query01 for PXF test on Multi Blocked data
SELECT count(*) FROM pxf_multi_block_read;

-- @description query02 for PXF test on Multi Blocked data
SELECT sum(a1) FROM pxf_multi_block_read;

-- @description query03 for PXF test on Multi Blocked data
SELECT t1, a1 FROM pxf_multi_block_read ORDER BY t1 LIMIT 10;

-- @description query04 for PXF test on Multi Blocked data
SELECT cnt < 32000000 AS check FROM (
	SELECT COUNT(*) AS cnt
		FROM pxf_multi_block_read
		WHERE gp_segment_id = 0
	) AS a;
