package com.lue.erp.auth;

import com.lue.erp.common.api.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class ProcurementErrorResponseTest {
    @Test void missingProcurementTableIsReportedAsDatabaseError() {
        var error = new BadSqlGrammarException("insert", "INSERT INTO goods_purchase_commands", new SQLException("table missing"));
        var response = new ApiExceptionHandler().databaseStructure(error);
        assertEquals(500, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("DATABASE_STRUCTURE_ERROR", response.getBody().code());
        assertTrue(response.getBody().message().contains("数据库结构"));
    }
}
