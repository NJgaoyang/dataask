package com.dataask.admin;

import com.dataask.audit.AuditTestDatabase;
import com.dataask.config.RuntimeConfigService;
import com.dataask.data.StarRocksGateway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataSourceControllerTest {
    JdbcTemplate jdbc;
    RuntimeConfigService settings;
    StarRocksGateway gateway;
    DataSourceController controller;
    @BeforeEach void setup() {
        jdbc=new JdbcTemplate(AuditTestDatabase.create());
        settings=new RuntimeConfigService(jdbc,"long-test-secret-at-least-thirty-two-bytes","jdbc:mysql://original:9030/ads","readonly","original-password","ads","","","");
        ReflectionTestUtils.invokeMethod(settings,"initializeEncryption");
        gateway=mock(StarRocksGateway.class);
        when(gateway.test(any())).thenReturn(Map.of("success",true));
        controller=new DataSourceController(jdbc,settings,gateway);
    }
    DataSourceController.Input input(String name,String password) { return new DataSourceController.Input(name,"jdbc:mysql://second:9030/ads","reader",password,"ads","测试连接"); }
    String create(String name,String password) { return (String)controller.create(input(name,password)).get("id"); }
    @Test void migrationKeepsOriginalSourceAndPermissionsAndIsIdempotent() {
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES('ads.orders',1)");
        controller.migrate(); controller.migrate();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasources",Long.class)).isEqualTo(1);
        assertThat(settings.starRocks().password()).isEqualTo("original-password");
        assertThat(settings.activeSourceId()).isNotBlank();
        assertThat(jdbc.queryForObject("SELECT enabled FROM da_allowed_tables",Integer.class)).isEqualTo(1);
    }
    @Test void multipleSourcesAreEncryptedPaginatedAndDoNotActivateOnSave() {
        controller.migrate();String active=settings.activeSourceId();
        for(int i=0;i<13;i++)create("测试源"+i,"source-password-"+i);
        assertThat(settings.activeSourceId()).isEqualTo(active);
        var page=controller.list(2,5,"");
        assertThat(page.get("total")).isEqualTo(14L);
        assertThat((List<?>)page.get("items")).hasSize(5);
        assertThat(page.toString()).doesNotContain("source-password-","password_enc","original-password");
        assertThat(jdbc.queryForObject("SELECT password_enc FROM da_datasources WHERE name='测试源1'",String.class)).startsWith("v1:").doesNotContain("source-password");
    }
    @Test void emptyPasswordNeverBorrowsAnotherSourcesSecretAndEditingKeepsOwnSecret() {
        controller.migrate();String id=create("新数据源","");
        controller.test(id,null);
        verify(gateway).test(argThat(c->c.password().isEmpty()));
        controller.update(id,input("新数据源","own-secret"));
        controller.update(id,input("新数据源",""));
        controller.test(id,null);
        verify(gateway).test(argThat(c->c.password().equals("own-secret")));
    }
    @Test void switchingTestsBeforeActivationAndDisablesOldGrantsAndDerivedIndex() {
        controller.migrate();String original=settings.activeSourceId(),id=create("新的源","own-password");
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES('ads.orders',1)");
        when(gateway.test(any())).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY,"连接失败"));
        assertThatThrownBy(()->controller.activate(id)).isInstanceOf(ResponseStatusException.class);
        assertThat(settings.activeSourceId()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT enabled FROM da_allowed_tables",Integer.class)).isEqualTo(1);
        doReturn(Map.of("success",true)).when(gateway).test(any());
        controller.activate(id);
        assertThat(settings.activeSourceId()).isEqualTo(id);
        assertThat(settings.starRocks().password()).isEqualTo("own-password");
        assertThat(settings.starRocks().jdbcUrl()).contains("second");
        assertThat(jdbc.queryForObject("SELECT enabled FROM da_allowed_tables",Integer.class)).isZero();
        assertThatThrownBy(()->controller.delete(id)).isInstanceOf(ResponseStatusException.class);
        controller.delete(original);
    }
    @Test void switchingBackRestoresOnlyTheOriginalSourcesTableGrants() {
        controller.migrate();
        String first=settings.activeSourceId();
        String second=create("第二个数据源","password-2");
        jdbc.update("INSERT INTO da_allowed_tables(table_name,enabled) VALUES('ads.sales',1)");
        controller.activate(second);
        assertThat(jdbc.queryForObject(
            "SELECT enabled FROM da_allowed_tables WHERE table_name='ads.sales'",Integer.class)).isZero();
        jdbc.update("INSERT INTO da_source_table_permissions(data_source_id,table_name,enabled) VALUES(?,?,1)",
            second,"ads.other_sales");
        controller.activate(first);
        assertThat(jdbc.queryForObject(
            "SELECT enabled FROM da_allowed_tables WHERE table_name='ads.sales'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM da_allowed_tables WHERE table_name='ads.other_sales' AND enabled=1",Long.class)).isZero();
        controller.activate(second);
        assertThat(jdbc.queryForObject(
            "SELECT enabled FROM da_allowed_tables WHERE table_name='ads.other_sales'",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT enabled FROM da_allowed_tables WHERE table_name='ads.sales'",Integer.class)).isZero();
    }

    @Test void refusesDeletingSourceWithReferencedMetricDefinitions() {
        controller.migrate();
        String newId=create("待删除数据源","password");
        String batch=java.util.UUID.randomUUID().toString();
        jdbc.update("INSERT INTO da_metric_import_batches(id,source_file,uploaded_by,row_count) " +
            "VALUES(?,?,?,1)",batch,"自动解析","admin");
        jdbc.update("INSERT INTO da_metric_definitions " +
            "(batch_id,metric_name,aliases,definition,ads_table,ads_field,time_field,dimensions,notes,sheet_name,excel_row) " +
            "VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            batch,"销售额","","待确认","ads.sales","amount","","city_id","","自动解析",1);
        Long metricId=jdbc.queryForObject(
            "SELECT MAX(id) FROM da_metric_definitions WHERE batch_id=?",Long.class,batch);
        jdbc.update("INSERT INTO da_metric_definition_sources(metric_id,data_source_id) VALUES(?,?)",
            metricId,newId);
        assertThatThrownBy(()->controller.delete(newId))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("关联的自动解析指标");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasources WHERE id=?",
            Long.class,newId)).isEqualTo(1);
        jdbc.update("DELETE FROM da_metric_definitions WHERE id=?",metricId);
        controller.delete(newId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM da_datasources WHERE id=?",
            Long.class,newId)).isZero();
    }

    @Test void invalidOrDuplicateSourceAndMutatingActiveConnectionAreRejected() {
        controller.migrate();create("重复源","pwd");
        assertThatThrownBy(()->create("重复源","pwd")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.update(settings.activeSourceId(),input("默认数据源","replacement"))).isInstanceOf(ResponseStatusException.class).hasMessageContaining("不可更换");
        assertThatThrownBy(()->controller.create(new DataSourceController.Input("危险源","jdbc:mysql://server:9030/ads?allowLoadLocalInfile=true","user","pwd","ads",""))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.list(0,10,"")).isInstanceOf(ResponseStatusException.class);
    }
}
