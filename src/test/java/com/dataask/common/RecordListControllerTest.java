package com.dataask.common;

import com.dataask.audit.AuditTestDatabase;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RecordListControllerTest {
    JdbcTemplate jdbc;RecordListController controller;
    Authentication alice=new UsernamePasswordAuthenticationToken("alice",null,List.of());
    @BeforeEach void setup(){jdbc=new JdbcTemplate(AuditTestDatabase.create());controller=new RecordListController(jdbc);for(String name:List.of("alice","bob"))jdbc.update("INSERT INTO da_users(username,display_name,password_hash,role) VALUES(?,?,?,'USER')",name,name,"unused");}
    @Test void sessionsPageBeyondOldFiftyLimitAndCannotSearchOtherUsers() {
        for(int i=0;i<65;i++)jdbc.update("INSERT INTO da_sessions(id,username,title) VALUES(?,?,?)",String.format("s%03d",i),"alice","问题"+i);
        jdbc.update("INSERT INTO da_sessions(id,username,title) VALUES('secret','bob','问题秘密')");
        var result=controller.sessions(alice,7,10,"");
        assertThat(result.get("total")).isEqualTo(65L);assertThat((List<?>)result.get("items")).hasSize(5);
        assertThat(controller.sessions(alice,1,10,"秘密").get("total")).isEqualTo(0L);
    }
    @Test void chartPaginationPreservesPrivateAndSelectedSharing() {
        for(String[] c:List.of(new String[]{"mine","alice","PRIVATE"},new String[]{"private","bob","PRIVATE"},new String[]{"all","bob","ALL"},new String[]{"selected","bob","SELECTED"},new String[]{"unselected","bob","SELECTED"}))jdbc.update("INSERT INTO da_saved_charts(id,owner_username,source_message_id,question,title,chart_type,share_scope) VALUES(?,?,?,'订单量','订单量','bar',?)",c[0],c[1],c[0],c[2]);
        jdbc.update("INSERT INTO da_chart_acl(chart_id,username) VALUES('selected','alice')");
        assertThat(controller.charts(alice,"mine",1,10,"","ALL","recent").get("total")).isEqualTo(1L);
        var shared=controller.charts(alice,"shared",2,1,"订单","bar","oldest");
        assertThat(shared.get("total")).isEqualTo(2L);assertThat((List<?>)shared.get("items")).hasSize(1);
        assertThat(shared.toString()).doesNotContain("unselected","private");
        assertThat(controller.charts(alice,"shared",1,10,"","line","recent").get("total")).isEqualTo(0L);
    }
    @Test void userFieldsAreRedactedAndSearchWildcardsAreLiteral() {
        assertThat(controller.users(1,10,"").toString()).doesNotContain("password_hash","unused");
        assertThat(controller.users(1,10,"%").get("total")).isEqualTo(0L);
        assertThat(controller.users(1,10,"ali").get("total")).isEqualTo(1L);
        assertThatThrownBy(()->controller.users(1,101,"")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.charts(alice,"all",1,10,"","ALL","recent")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->controller.charts(alice,"mine",1,10,"","ALL","DROP TABLE")).isInstanceOf(ResponseStatusException.class);
    }
    @Test void metricAndImportListsArePagedWithoutOldTruncation() {
        for(int i=0;i<32;i++){jdbc.update("INSERT INTO da_metric_import_batches(id,source_file,uploaded_by,row_count) VALUES(?,?,?,?)","b"+i,"file"+i+".xlsx","alice",4);for(int j=0;j<4;j++)jdbc.update("INSERT INTO da_metric_definitions(batch_id,metric_name,definition,notes,sheet_name,excel_row) VALUES(?,?,'实际口径','','Sheet1',1)","b"+i,"订单指标"+i+"-"+j);}
        assertThat(controller.batches(4,10,"").get("total")).isEqualTo(32L);
        assertThat((List<?>)controller.batches(4,10,"").get("items")).hasSize(2);
        assertThat(controller.metrics(13,10,"").get("total")).isEqualTo(128L);
        assertThat((List<?>)controller.metrics(13,10,"").get("items")).hasSize(8);
    }
}
