package com.dataask.audit;

import com.dataask.data.StarRocksGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

class AuditPersistenceTest {
    private final JdbcTemplate jdbc = new JdbcTemplate(AuditTestDatabase.create());
    private final AuditService audit = new AuditService(jdbc);
    private final AuditController controller = new AuditController(jdbc);
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); }

    @Test void exactSqlIsPersistedBeforeExecutionAndIncludesRefreshFailureAndCancellation() {
        String sql = "SELECT city, SUM(orders) FROM ads.orders WHERE dt='2026-11-09' GROUP BY city";
        var result = new StarRocksGateway.QueryResult(List.of("city"),List.of(Map.of("city","西安市")));
        audit.query("alice","CHAT","session","订单量",sql,()-> {
            assertThat(jdbc.queryForObject("SELECT sql_text FROM da_query_audit WHERE status='RUNNING'",String.class)).isEqualTo(sql);
            return result;
        });
        assertThat(audit.query("bob","CHART_REFRESH",null,"刷新图表",sql,()->result)).isEqualTo(result);
        assertThatThrownBy(()->audit.query("alice","CHAT",null,"失败查询",sql,()-> {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"secret provider detail must not be logged");
        })).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->audit.query("alice","CHAT",null,"停止查询",sql,()-> {
            throw new ResponseStatusException(HttpStatus.CONFLICT,"本次查询已停止");
        })).isInstanceOf(ResponseStatusException.class);
        assertThat(jdbc.queryForList("SELECT status FROM da_query_audit",String.class))
            .containsExactlyInAnyOrder("SUCCESS","SUCCESS","FAILED","CANCELLED");
        assertThat(jdbc.queryForObject("SELECT row_count FROM da_query_audit WHERE username='bob'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT error_code FROM da_query_audit WHERE status='FAILED'",String.class)).isEqualTo("QUERY_FAILED");
        // A separate reader sees durable rows; this is not an in-memory log list.
        assertThat(new JdbcTemplate(jdbc.getDataSource()).queryForObject("SELECT COUNT(*) FROM da_query_audit",Integer.class)).isEqualTo(4);
    }

    @Test void databaseFailurePreventsUnloggedBusinessQuery() {
        JdbcTemplate unavailable = org.mockito.Mockito.mock(JdbcTemplate.class);
        org.mockito.Mockito.when(unavailable.update(org.mockito.ArgumentMatchers.startsWith("INSERT INTO da_query_audit"),org.mockito.ArgumentMatchers.any(Object[].class)))
            .thenThrow(new org.springframework.dao.DataAccessResourceFailureException("audit unavailable"));
        var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        assertThatThrownBy(()->new AuditService(unavailable).query("alice","CHAT",null,"订单量","SELECT 1",()-> {
            invoked.set(true); return new StarRocksGateway.QueryResult(List.of(),List.of());
        })).isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
        assertThat(invoked).isFalse();
    }

    @Test void todayDefaultsToShanghaiMidnightUsingIndexedUtcBounds() {
        var search = AuditController.search(Map.of(),false,LocalDate.of(2026,11,10));
        assertThat(search.where()).isEqualTo(" WHERE occurred_at>=? AND occurred_at<?");
        assertThat(search.args()).containsExactly(LocalDateTime.of(2026,11,9,16,0),LocalDateTime.of(2026,11,10,16,0));
        assertThat(AuditController.search(Map.of("allDates","true"),true,LocalDate.now()).where()).isEmpty();
        assertThatThrownBy(()->controller.logs(Map.of("dateFrom","2026-11-11","dateTo","2026-11-10")))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.logs(Map.of("pageSize","101"))).isInstanceOf(ResponseStatusException.class);
    }

    @Test void combinedFiltersAreBoundAndKeywordWildcardsAreLiteral() {
        operation("alice","智能问数","100%_ orders");
        operation("bob","智能问数","100XX orders");
        var page = controller.logs(Map.of("allDates","true","username","alice","module","CHAT","status","SUCCESS","keyword","100%_","pageSize","1"));
        assertThat(page.get("total")).isEqualTo(1L);
        assertThat((List<?>)page.get("items")).hasSize(1);
        assertThat(controller.logs(Map.of("allDates","true","username","alice' OR 1=1 --")).get("total")).isEqualTo(0L);
        assertThat(controller.logs(Map.of("allDates","true","pageSize","1","page","2")).get("items")).asList().hasSize(1);
    }

    @Test void batchDeletePreservesItsOwnOperationAndUnselectedHistory() {
        String removed = operation("alice","智能问数","订单"), retained = operation("bob","查看图表","图表");
        String own = UUID.randomUUID().toString();
        audit.beginOperation(own,"admin","ADMIN","POST","/api/admin/audit/logs/batch-delete","AUDIT","批量删除","审计");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        AuditContext.set("id",own);
        assertThat(controller.deleteLogs(new AuditController.DeleteRequest(List.of(removed,own,removed))).get("deleted")).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT id FROM da_operation_audit",String.class)).containsExactlyInAnyOrder(own,retained);
        assertThat(AuditContext.get("detail")).contains("实际删除 1 条",removed).doesNotContain(retained);
        assertThatThrownBy(()->controller.deleteLogs(new AuditController.DeleteRequest(List.of("' OR 1=1"))))
            .isInstanceOf(ResponseStatusException.class);
    }

    @Test void adminListOmitsSqlUntilDetailAndBothTablesHaveSearchIndexes() {
        audit.query("alice","CHAT",null,"订单","SELECT 42 FROM ads.orders",()->new StarRocksGateway.QueryResult(List.of(),List.of()));
        var items = (List<?>)controller.queries(Map.of("allDates","true")).get("items");
        Map<?,?> item = (Map<?,?>)items.getFirst();
        assertThat(item.containsKey("sql")).isFalse();
        assertThat(controller.queryDetail((String)item.get("id"))).containsEntry("sql","SELECT 42 FROM ads.orders");
        assertThat((String)item.get("occurredAt")).endsWith("Z");
        var indexes = jdbc.queryForList("SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES",String.class);
        assertThat(indexes).contains("idx_da_op_time","idx_da_op_user_time","idx_da_op_module_time","idx_da_op_action_time",
            "idx_da_op_request","idx_da_query_time","idx_da_query_user_time","idx_da_query_status_time","idx_da_query_source_time","idx_da_query_request");
    }

    private String operation(String username,String action,String target) {
        String id = UUID.randomUUID().toString();
        audit.beginOperation(id,username,"USER","POST","/api/chat/ask","CHAT",action,target);
        audit.finishOperation(id,username,"USER",action,target,"",200,5);
        return id;
    }
}
