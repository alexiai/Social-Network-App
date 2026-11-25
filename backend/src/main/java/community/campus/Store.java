package community.campus;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class Store {
    private final JdbcTemplate jdbc;

    Store(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Map<String, Object>> rows(String sql, Object... parameters) {
        return jdbc.queryForList(sql, parameters);
    }

    Map<String, Object> one(String sql, Object... parameters) {
        return rows(sql, parameters).stream().findFirst().orElseThrow(ApiErrors::missing);
    }

    <T> Optional<T> value(Class<T> type, String sql, Object... parameters) {
        return jdbc.queryForList(sql, type, parameters).stream().findFirst();
    }

    boolean exists(String sql, Object... parameters) {
        return value(Boolean.class, "select exists (" + sql + ")", parameters).orElse(false);
    }

    int write(String sql, Object... parameters) {
        return jdbc.update(sql, parameters);
    }
}
