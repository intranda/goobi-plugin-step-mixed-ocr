package de.intranda.goobi.plugins.step.mixedocr;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.apache.commons.dbutils.QueryRunner;

import de.sub.goobi.persistence.managers.MySQLHelper;

public class MixedOcrDao {
    /**
     * Schema:
     * 
     * CREATE TABLE IF NOT EXISTS `ocrjobs` ( `ocrjob_id` int(11) NOT NULL AUTO_INCREMENT, `step_id` int(11) NOT NULL, `fracture_done` tinyint(1)
     * DEFAULT false, `antiqua_done` tinyint(1) DEFAULT false, PRIMARY KEY (`ocrjob_id`) ) DEFAULT CHARSET=utf8mb4;
     * 
     */

    public static long addJob(int stepId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            String sql = "INSERT INTO ocrjobs (step_id, fracture_done, antiqua_done) VALUES (?,?,?)";
            QueryRunner run = new QueryRunner();
            return run.insert(conn, sql, MySQLHelper.resultSetToLongHandler, stepId, false, false);
        }
    }

    public static void setJobDone(long jobId, boolean fracture) throws SQLException {
        StringBuilder sql = new StringBuilder();
        sql.append("UPDATE ocrjobs SET ");
        if (fracture) {
            sql.append("fracture_done=true");
        } else {
            sql.append("antiqua_done=true");
        }
        sql.append(" WHERE ocrjob_id=?;");
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            QueryRunner run = new QueryRunner();
            run.update(conn, sql.toString(), jobId);
        }
    }

    public static boolean isJobDone(long jobId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            QueryRunner run = new QueryRunner();
            return run.query(conn, "SELECT * FROM ocrjobs WHERE ocrjob_id=? AND antiqua_done=true AND fracture_done=true;",
                    ResultSet::next, jobId);
        }
    }

    public static int getStepIdForJob(long jobId) throws SQLException {
        try (Connection conn = MySQLHelper.getInstance().getConnection()) {
            String sql = "SELECT step_id from ocrjobs WHERE ocrjob_id=?";
            QueryRunner run = new QueryRunner();
            return run.query(conn, sql, MySQLHelper.resultSetToIntegerHandler, jobId);
        }
    }
}
