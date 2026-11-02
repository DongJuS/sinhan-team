package com.sinhan.order.infrastructure;

import com.sinhan.contracts.*;
import com.sinhan.contracts.Models.*;
import com.sinhan.order.domain.OrderState;
import com.sinhan.web.Problem;
import java.util.*;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OrderRepository {
  public record Job(String id, String requestId, String kind, String owner, int attempts) {}

  private final JdbcTemplate db;
  private final TransactionTemplate tx;

  public OrderRepository(JdbcTemplate d, TransactionTemplate t) {
    db = d;
    tx = t;
  }

  public OrderState create(String user, String key, PurchaseInput input) {
    return tx.execute(
        s -> {
          db.queryForList("select pg_advisory_xact_lock(hashtextextended(?,0))", user + ":" + key);
          var old =
              db.queryForList(
                  "select payload,input_hash from requests where user_id=? and idempotency_key=?",
                  user,
                  key);
          if (!old.isEmpty()) {
            if (!old.getFirst().get("input_hash").equals(Canonical.hash(input)))
              throw new Problem(409, "IDEMPOTENCY_CONFLICT", "동일 키에 다른 요청입니다.");
            return Canonical.read(old.getFirst().get("payload").toString(), OrderState.class);
          }
          String id = UUID.randomUUID().toString();
          var state =
              new OrderState(
                  id,
                  user,
                  input,
                  "SEARCHING",
                  "SEARCH",
                  "추천 후보를 찾고 있습니다.",
                  null,
                  null,
                  List.of(),
                  null,
                  0);
          db.update(
              "insert into requests(id,user_id,idempotency_key,input_hash,payload)"
                  + " values(?,?,?,?,?::jsonb)",
              id,
              user,
              key,
              Canonical.hash(input),
              Canonical.json(state));
          enqueue(id, "PLAN");
          return state;
        });
  }

  public OrderState get(String id) {
    var rows = db.queryForList("select payload from requests where id=?", id);
    if (rows.isEmpty()) throw new Problem(404, "REQUEST_NOT_FOUND", "구매 요청을 찾을 수 없습니다.");
    return Canonical.read(rows.getFirst().get("payload").toString(), OrderState.class);
  }

  public Proposal proposal(String id) {
    var rows = db.queryForList("select payload from proposals where id=?", id);
    if (rows.isEmpty()) throw new Problem(404, "PROPOSAL_NOT_FOUND", "주문안을 찾을 수 없습니다.");
    return Canonical.read(rows.getFirst().get("payload").toString(), Proposal.class);
  }

  public void approved(Proposal p) {
    tx.executeWithoutResult(
        s -> {
          lockRequest(p.requestId());
          var r = get(p.requestId());
          if (r.proposal() == null || !r.proposal().id().equals(p.id()))
            throw new Problem(409, "STALE_PROPOSAL", "최신 주문안을 승인해주세요.");
          if (Set.of("EXECUTING", "RECOVERY_PENDING", "COMPLETED", "FAILED_REFUNDED")
              .contains(r.status())) return;
          if (!r.status().equals("AWAITING_APPROVAL"))
            throw new Problem(409, "INVALID_STATE", "지금 승인할 수 없습니다.");
          save(r.progress("EXECUTING", "START", "승인된 주문을 실행하고 있습니다.", null));
          enqueue(r.id(), "EXECUTE");
        });
  }

  public void refresh(Proposal p, String error) {
    tx.executeWithoutResult(
        s -> {
          lockRequest(p.requestId());
          var r = get(p.requestId());
          if (r.proposal() != null
              && r.proposal().id().equals(p.id())
              && r.status().equals("AWAITING_APPROVAL")) {
            save(r.progress("REPLANNING", "SEARCH", "조건이 변경되어 새 주문안을 만들고 있습니다.", error));
            enqueue(r.id(), "PLAN");
          }
        });
  }

  public Job claim(String owner) {
    return tx.execute(
        s -> {
          var rows =
              db.queryForList(
                  "with picked as (select id from jobs where (status='READY' and"
                      + " available_at<=now()) or (status='RUNNING' and lease_until<now()) order by"
                      + " available_at for update skip locked limit 1) update jobs j set"
                      + " status='RUNNING',owner=?,lease_until=now()+interval '45"
                      + " seconds',attempts=attempts+1 from picked where j.id=picked.id returning"
                      + " j.*",
                  owner);
          if (rows.isEmpty()) return null;
          var r = rows.getFirst();
          return new Job(
              r.get("id").toString(),
              r.get("request_id").toString(),
              r.get("kind").toString(),
              owner,
              ((Number) r.get("attempts")).intValue());
        });
  }

  public void renew(Job j) {
    db.update(
        "update jobs set lease_until=now()+interval '45 seconds' where id=? and owner=? and"
            + " status='RUNNING'",
        j.id(),
        j.owner());
  }

  public OrderState change(Job j, UnaryOperator<OrderState> fn) {
    return tx.execute(
        s -> {
          owned(j);
          lockRequest(j.requestId());
          var state = fn.apply(get(j.requestId()));
          save(state);
          return state;
        });
  }

  public void planned(Job j, OrderState state) {
    tx.executeWithoutResult(
        s -> {
          owned(j);
          db.update(
              "insert into proposals(id,request_id,payload) values(?,?,?::jsonb) on conflict(id) do"
                  + " nothing",
              state.proposal().id(),
              state.id(),
              Canonical.json(state.proposal()));
          save(state);
          done(j);
        });
  }

  public void finish(Job j, OrderState state) {
    tx.executeWithoutResult(
        s -> {
          owned(j);
          save(state);
          done(j);
        });
  }

  public void replan(Job j, OrderState state) {
    tx.executeWithoutResult(
        s -> {
          owned(j);
          save(state.progress("REPLANNING", "SEARCH", "조건이 바뀌어 새 주문안을 제안합니다.", state.errorCode()));
          done(j);
          enqueue(state.id(), "PLAN");
        });
  }

  public void retry(Job j, String status, String message, String error) {
    tx.executeWithoutResult(
        s -> {
          owned(j);
          var r = get(j.requestId());
          save(r.progress(status, r.stage(), message, error));
          db.update(
              "update jobs set status='READY',owner=null,lease_until=null,available_at=now()+(? *"
                  + " interval '1 second') where id=?",
              Math.min(30, 1 << Math.min(j.attempts(), 4)),
              j.id());
        });
  }

  public void complete(Job j) {
    tx.executeWithoutResult(
        s -> {
          owned(j);
          done(j);
        });
  }

  private void owned(Job j) {
    var rows =
        db.queryForList(
            "select owner,status,lease_until>now() as active from jobs where id=? for update",
            j.id());
    if (rows.isEmpty()
        || !j.owner().equals(rows.getFirst().get("owner"))
        || !Boolean.TRUE.equals(rows.getFirst().get("active"))
        || !rows.getFirst().get("status").equals("RUNNING"))
      throw new IllegalStateException("LEASE_LOST");
  }

  private void lockRequest(String id) {
    db.queryForList("select id from requests where id=? for update", id);
  }

  private void save(OrderState s) {
    db.update(
        "update requests set payload=?::jsonb,updated_at=now() where id=?",
        Canonical.json(s),
        s.id());
  }

  private void done(Job j) {
    db.update(
        "update jobs set status='DONE',lease_until=null where id=? and owner=?", j.id(), j.owner());
  }

  private void enqueue(String id, String kind) {
    db.update(
        "insert into jobs(id,request_id,kind,status) values(?,?,?,'READY')",
        UUID.randomUUID().toString(),
        id,
        kind);
  }
}
