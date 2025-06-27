package com.wavjaby.jdbc.util;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class FastResultSetExtractor<T> implements ResultSetExtractor<List<T>> {
    public final FastRowMapper<T> rowMapper;


    public FastResultSetExtractor(Class<T> mappedClass, int columnCount) {
        this.rowMapper = new FastRowMapper<>(mappedClass, columnCount);
    }

    public FastResultSetExtractor(Class<T> mappedClass, String tableName, JdbcTemplate jdbc) {
        this.rowMapper = new FastRowMapper<>(mappedClass, tableName, jdbc);
    }

    @Override
    public List<T> extractData(ResultSet rs) throws SQLException, DataAccessException {
        List<T> results = new ArrayList<>();
        int rowNum = 0;
        while (rs.next())
            results.add(rowMapper.mapRow(rs, rowNum++));

        return results;
    }
}
