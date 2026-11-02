package com.sinhan.control.infrastructure;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {
  private final JdbcTemplate db;

  public ApprovalRepository(JdbcTemplate d) {
    db = d;
  }

  public void lock(String id) {
    db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", id);
  }

  public Map<String, Object> get(String id) {
    var r = db.queryForList("select * from approvals where proposal_id=?", id);
    return r.isEmpty() ? null : r.getFirst();
  }

  public void prepare(String id, String user, String execution, String payload, String digest) {
    db.update(
        "insert into approvals(proposal_id,user_id,execution_id,payload,digest,status)"
            + " values(?,?,?,?::jsonb,?,'PREPARED') on conflict(proposal_id) do nothing",
        id,
        user,
        execution,
        payload,
        digest);
  }

  public void status(String id, String status) {
    db.update("update approvals set status=?,updated_at=now() where proposal_id=?", status, id);
  }

  public void audit(String id, String action) {
    db.update("insert into audit(proposal_id,action) values(?,?)", id, action);
  }
}
